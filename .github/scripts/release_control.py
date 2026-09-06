import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')
    temporary.replace(path)


def properties(path):
    result = {}
    for line in path.read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            if key.strip() in result:
                raise ValueError(f'duplicate property: {key.strip()}')
            result[key.strip()] = value.strip()
    return result


def root_version(root):
    version = properties(root / 'gradle.properties')['VERSION_NAME']
    if not re.fullmatch(r'[0-9][0-9A-Za-z.+-]*', version):
        raise ValueError('invalid root version')
    for path in root.glob('*/gradle.properties'):
        if 'VERSION_NAME' in properties(path):
            raise ValueError(f'version override in {path}')
    return version


def validate_jobs(needs, required):
    missing = set(required) - set(needs)
    if missing:
        raise ValueError('missing validation jobs: ' + ', '.join(sorted(missing)))
    for name in required:
        if needs[name].get('result') != 'success':
            raise ValueError(f'{name} must finish with success')


def validate_job_provenance(context, version, needs, required):
    validate_jobs(needs, required)
    for name in required:
        output = needs[name].get('outputs', {})
        if output.get('source_sha') != context['sha']:
            raise ValueError(f'{name} validated a different SHA')
        if any(str(output.get(key)) != str(context[key]) for key in ['run_id', 'run_attempt']):
            raise ValueError(f'{name} has different run provenance')
        if output.get('version') != version:
            raise ValueError(f'{name} validated a different version')


def validate_context(context, version, manifest):
    if context['repository'] != manifest['repository']:
        raise ValueError('publication repository is forbidden')
    if not re.fullmatch(r'[0-9a-f]{40}', context['sha']) or context['sha'] != context['checked_out_sha']:
        raise ValueError('checked-out SHA does not match the workflow SHA')
    if not all(str(context[key]).isdigit() for key in ['run_id', 'run_attempt']):
        raise ValueError('missing run provenance')
    if context['event'] == 'push' and context['ref'].startswith('refs/tags/v'):
        if version.endswith('-SNAPSHOT'):
            raise ValueError('SNAPSHOT tags cannot publish')
        if context['ref'] != f'refs/tags/v{version}':
            raise ValueError('tag and root version differ')
    elif context['event'] == 'workflow_dispatch' and context['ref'].startswith('refs/heads/'):
        if not version.endswith('-SNAPSHOT'):
            raise ValueError('workflow_dispatch publishes snapshots only')
    else:
        raise ValueError('unsupported publication event or ref')


def release_evidence(context, version, manifest, needs):
    validate_context(context, version, manifest)
    validate_job_provenance(context, version, needs, manifest['release_jobs'])
    return dict(schema_version=1, source_sha=context['sha'], version=version,
                repository=context['repository'], ref=context['ref'],
                run_id=context['run_id'], run_attempt=context['run_attempt'],
                classification='validated', artifacts=manifest['artifacts'],
                checks=needs, matrix_jobs=manifest['matrix_jobs'])


def release_notes(path, version):
    text = path.read_text()
    sections = re.split(r'(?m)^## ', text)
    selected = [section for section in sections[1:] if section.startswith(f'[{version}]')]
    if len(selected) != 1:
        raise ValueError(f'exactly one release-note section required for {version}')
    heading, _, body = selected[0].partition('\n')
    if not re.fullmatch(r'\[' + re.escape(version) + r'\] \(\d{4}-\d{2}-\d{2}\)', heading.strip()):
        raise ValueError('release notes need a dated version heading')
    if not body.strip() or re.search(r'\b(TODO|TBD|FIXME)\b|\[Unreleased\]', body):
        raise ValueError('release notes are empty or contain placeholders')
    return '## ' + selected[0].strip() + '\n'


def require_new_release(existing):
    if existing is not None:
        raise ValueError('A release record already exists. Inspect its receipt and use record repair; do not republish.')


def publish_modules(record, path, publish):
    if Path(path).exists():
        raise ValueError('publication receipt already exists; inspect and repair instead of republishing')
    receipt = dict(record, publication_status='started', published_modules=[])
    write_json(path, receipt)
    for module in record['artifacts']:
        receipt['attempting_module'] = module
        write_json(path, receipt)
        task = 'publishToMavenCentral' if record['version'].endswith('-SNAPSHOT') else 'publishAndReleaseToMavenCentral'
        try:
            publish(f':{module}:{task}')
        except BaseException:
            receipt['publication_status'] = 'partial'
            write_json(path, receipt)
            raise
        receipt['published_modules'].append(module)
        receipt.pop('attempting_module')
        write_json(path, receipt)
    receipt['publication_status'] = 'complete'
    write_json(path, receipt)
    return receipt


def repair_record(receipt, update):
    if receipt.get('publication_status') != 'complete' or receipt.get('published_modules') != receipt.get('artifacts'):
        raise ValueError('Maven publication receipt is incomplete; reconcile Central before record repair')
    update(receipt)


def publication_versions(repository, version, group, modules, artifacts, expected_publications=None):
    if not expected_publications or len(expected_publications) != len(set(expected_publications)):
        raise ValueError('an explicit, nonduplicated expected publication inventory is required')
    if not set(modules).issubset(expected_publications):
        raise ValueError('expected publication inventory omits a module root')
    discovered = {path.name for path in repository.iterdir() if (path / version).is_dir()
                  and any(path.name == module or path.name.startswith(module + '-') for module in modules)}
    unexpected = discovered - set(expected_publications)
    if unexpected:
        raise ValueError('unexpected publication outside the inventory: ' + ', '.join(sorted(unexpected)))
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    count = 0
    for artifact in expected_publications:
        pom = repository / artifact / version / f'{artifact}-{version}.pom'
        if not pom.is_file():
            raise ValueError(f'missing publication POM: {pom}')
        root = ET.parse(pom).getroot()
        if root.findtext('m:version', namespaces=ns) != version:
            raise ValueError(f'publication version differs in {pom}')
        if root.findtext('m:groupId', namespaces=ns) != group:
            raise ValueError(f'publication group differs in {pom}')
        if root.findtext('m:artifactId', namespaces=ns) != artifact:
            raise ValueError(f'publication artifact differs in {pom}')
        dependencies = root.findall('.//m:dependency', ns)
        for dependency in dependencies:
            if dependency.findtext('m:groupId', namespaces=ns) == group:
                if dependency.findtext('m:version', namespaces=ns) != version:
                    raise ValueError(f'internal dependency version differs in {pom}')
        if artifact == 'bom':
            constraints = root.findall('m:dependencyManagement/m:dependencies/m:dependency', ns)
            names = [dependency.findtext('m:artifactId', namespaces=ns) for dependency in constraints]
            if sorted(names) != sorted(set(artifacts) - {'bom'}):
                raise ValueError('BOM inventory differs from the publication allowlist')
        metadata = pom.with_suffix('.module')
        if not metadata.is_file():
            raise ValueError(f'missing Gradle module metadata: {metadata}')
        data = json.loads(metadata.read_text())
        component = data.get('component', {})
        component_module = component.get('module')
        if component_module == artifact:
            if 'url' in component:
                raise ValueError(f'unexpected self-component URL in {metadata}')
        else:
            owners = [module for module in modules if artifact.startswith(module + '-')]
            owner = max(owners, key=len) if owners else None
            if owner is None or component_module != owner or artifact in modules:
                raise ValueError(f'Gradle component owner differs in {metadata}')
            # KMP target metadata identifies its root component through this relative URL.
            expected_url = f'../../{owner}/{version}/{owner}-{version}.module'
            if component.get('url') != expected_url:
                raise ValueError(f'Gradle component URL differs in {metadata}')
            owner_metadata = (metadata.parent / expected_url).resolve()
            if not owner_metadata.is_relative_to(repository.resolve()):
                raise ValueError(f'Gradle component URL escapes the repository in {metadata}')
            if not owner_metadata.is_file():
                raise ValueError(f'missing owning Gradle module metadata: {owner_metadata}')
        if any(component.get(key) != value for key, value in
               dict(group=group, module=component_module, version=version).items()):
            raise ValueError(f'Gradle module coordinates differ in {metadata}')
        for variant in data.get('variants', []):
            for dependency in variant.get('dependencies', []) + variant.get('dependencyConstraints', []):
                if dependency.get('group') == group:
                    constraint = dependency.get('version', {})
                    if not constraint or any(value != version for key, value in constraint.items()
                                             if key in ['requires', 'strictly', 'prefers']):
                        raise ValueError(f'Gradle dependency version differs in {metadata}')
            available = variant.get('available-at', {})
            if available.get('group') == group and available.get('version') != version:
                raise ValueError(f'Gradle target version differs in {metadata}')
        count += 1
    return count


def task_outcome(log):
    matches = re.findall(r'^> Task :mutations:jvmTest(?:[ \t]+([^\r\n]*))?\r?$', log, re.MULTILINE)
    outcomes = [match.strip() for match in matches]
    if not outcomes or any(outcome not in ['', 'FAILED'] for outcome in outcomes):
        raise ValueError('expected an actually executed :mutations:jvmTest task')
    return 'failed' if 'FAILED' in outcomes else 'executed'


def test_identifiers(results, expected):
    identifiers = []
    executed = set()
    for path in sorted(results.glob('TEST-*.xml')):
        for testcase in ET.parse(path).getroot().iter('testcase'):
            classname = testcase.get('classname', '')
            outcome = 'passed'
            if testcase.find('skipped') is not None:
                outcome = 'skipped'
            elif testcase.find('failure') is not None or testcase.find('error') is not None:
                outcome = 'failed'
            if outcome != 'skipped':
                executed.add(classname)
            identifiers.append(dict(id=classname + '#' + testcase.get('name', ''), outcome=outcome))
    missing = set(expected) - executed
    if missing or not identifiers:
        raise ValueError('no executed testcase evidence for: ' + ', '.join(sorted(missing)))
    return identifiers


def context_from_env():
    return dict(repository=os.environ['GITHUB_REPOSITORY'], event=os.environ['GITHUB_EVENT_NAME'],
                ref=os.environ['GITHUB_REF'], sha=os.environ['GITHUB_SHA'],
                checked_out_sha=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                run_id=os.environ['GITHUB_RUN_ID'], run_attempt=os.environ['GITHUB_RUN_ATTEMPT'])


def append_outputs(context, version):
    if context['sha'] != context['checked_out_sha']:
        raise ValueError('checked-out SHA changed')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        for key, value in [('source_sha', context['sha']), ('run_id', context['run_id']),
                           ('run_attempt', context['run_attempt']), ('version', version)]:
            output.write(f'{key}={value}\n')


def instrumentation_failures(log, results, log_path):
    failures = []

    def collect(text, source, path, stream_index=None):
        for line_number, line in enumerate(text.split('\n'), 1):
            match = re.fullmatch(r"[ \t]*Unable to transform (.+)", line)
            if match:
                failure = dict(source=source, path=path, line=line_number, class_name=match[1])
                if stream_index is not None:
                    failure['stream_index'] = stream_index
                failures.append(failure)

    collect(log, 'gradle-log', str(log_path))
    for path in sorted(results.glob('TEST-*.xml')):
        for index, stream in enumerate(ET.parse(path).getroot().iter('system-err'), 1):
            collect(''.join(stream.itertext()), 'xml-system-err', path.name, index)
    return failures


def full_suite_record(args, context, manifest):
    output = Path(args.output)
    log = Path(args.log).read_text()
    version = root_version(ROOT)
    record = dict(schema_version=1, source_sha=context['sha'], checked_out_sha=context['checked_out_sha'], version=version,
                  repository=context['repository'], run_id=context['run_id'], run_attempt=context['run_attempt'],
                  sequence=args.sequence, task=':mutations:jvmTest', gradle_exit_code=args.exit_code,
                  classification='unexecuted', log_sha256=hashlib.sha256(log.encode()).hexdigest())
    try:
        record['task_outcome'] = task_outcome(log)
        record['classification'] = 'infrastructure-or-incomplete'
        expected = []
        for source in sorted((ROOT / 'mutations/src').glob('**/*Test.kt')):
            if '/commonTest/' in str(source) or '/jvmTest/' in str(source):
                package = re.search(r'(?m)^package (\S+)', source.read_text())
                if package:
                    expected.append(package[1] + '.' + source.stem)
        record['expected_classes'] = expected
        results = Path(args.results)
        record['xml_files'] = {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                               for path in sorted(results.glob('TEST-*.xml'))}
        record['test_identifiers'] = test_identifiers(results, [])
        test_identifiers(results, expected)
        record['instrumentation_failures'] = instrumentation_failures(log, results, args.log)
        if record['instrumentation_failures']:
            record['evidence_error'] = 'Lincheck reported a bytecode transformation failure'
        if context['sha'] != context['checked_out_sha']:
            raise ValueError('source SHA changed during test execution')
        if any(item['outcome'] == 'failed' for item in record['test_identifiers']):
            record['classification'] = 'test-failure'
        elif record['instrumentation_failures']:
            raise ValueError(record['evidence_error'])
        elif args.exit_code == 0 and record['task_outcome'] == 'executed':
            record['classification'] = 'passed'
        else:
            raise ValueError('Gradle failed without test failure evidence')
    except (ValueError, OSError, ET.ParseError) as error:
        record['evidence_error'] = str(error)
    write_json(output, record)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as handle:
            handle.write(f"### Full mutations JVM execution {args.sequence}\n\n"
                         f"Classification: **{record['classification']}**. Source: `{context['sha']}`. "
                         f"Run: `{context['run_id']}`, attempt: `{context['run_attempt']}`.\n\n"
                         f"Task outcome: `{record.get('task_outcome', 'unexecuted')}`. "
                         f"Gradle exit: `{args.exit_code}`.\n\n"
                         "The result artifact contains the console log, XML, test identifiers and hashes. "
                         "For a failure, preserve this first result, classify the failed test or infrastructure cause, "
                         "and link the fix or disposition from this run. Do not rerun unchanged failures for green.\n")
    if record['classification'] != 'passed':
        raise ValueError('full-suite evidence did not pass; inspect the archived first result')
    append_outputs(context, version)


def gh(*args, **kwargs):
    return subprocess.run(['gh', *args], check=True, text=True, **kwargs)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['version', 'provenance', 'matrix', 'full-suite-pair', 'gate', 'reserve',
                                           'publish', 'record', 'full-suite', 'publication-versions'])
    parser.add_argument('--output', default='release-evidence.json')
    parser.add_argument('--receipt', default='publication-receipt.json')
    parser.add_argument('--repository')
    parser.add_argument('--modules', nargs='+')
    parser.add_argument('--publications', nargs='+')
    parser.add_argument('--log')
    parser.add_argument('--results')
    parser.add_argument('--sequence', type=int)
    parser.add_argument('--exit-code', type=int)
    args = parser.parse_args()
    version = root_version(ROOT)
    manifest = json.loads((ROOT / '.github/release-manifest.json').read_text())
    if args.command == 'version':
        print(version)
        return
    if args.command == 'publication-versions':
        count = publication_versions(Path(args.repository), version, properties(ROOT / 'gradle.properties')['GROUP'],
                                     args.modules, manifest['artifacts'], args.publications)
        print(f'Validated {count} publication POMs and Gradle metadata files at version {version}')
        return
    context = context_from_env()
    if args.command == 'provenance':
        append_outputs(context, version)
        write_json(args.output, dict(context, version=version, classification='validated'))
    elif args.command in ['matrix', 'full-suite-pair']:
        needs = json.loads(os.environ['VALIDATION_NEEDS'])
        required = manifest['matrix_jobs' if args.command == 'matrix' else 'full_suite_jobs']
        validate_job_provenance(context, version, needs, required)
        append_outputs(context, version)
        write_json(args.output, dict(context, version=version, checks=needs, classification='validated'))
    elif args.command == 'gate':
        record = release_evidence(context, version, manifest, json.loads(os.environ['VALIDATION_NEEDS']))
        if not version.endswith('-SNAPSHOT'):
            Path('release-notes.md').write_text(release_notes(ROOT / 'CHANGELOG.md', version))
        write_json(args.output, record)
    elif args.command == 'reserve':
        record = json.loads(Path(args.output).read_text())
        validate_context(context, version, manifest)
        if record['source_sha'] != context['sha'] or record['version'] != version:
            raise ValueError('release evidence version or SHA differs')
        if version.endswith('-SNAPSHOT'):
            return
        tag = f'v{version}'
        existing = subprocess.run(['gh', 'release', 'view', tag, '--repo', context['repository'], '--json', 'tagName'],
                                  text=True, capture_output=True)
        require_new_release(json.loads(existing.stdout) if existing.returncode == 0 else None)
        gh('release', 'create', tag, '--repo', context['repository'], '--draft', '--verify-tag',
           '--title', tag, '--notes-file', 'release-notes.md')
    elif args.command == 'publish':
        record = json.loads(Path(args.output).read_text())
        validate_context(context, version, manifest)
        if record['source_sha'] != context['sha'] or record['version'] != version:
            raise ValueError('release evidence version or SHA differs')
        publish_modules(record, args.receipt, lambda task: subprocess.run(['./gradlew', task, '--stacktrace'], check=True))
    elif args.command == 'record':
        receipt = json.loads(Path(args.receipt).read_text())
        if context['repository'] != manifest['repository'] or receipt['repository'] != context['repository']:
            raise ValueError('record repair repository differs')
        if receipt['source_sha'] != context['checked_out_sha'] or receipt['version'] != version:
            raise ValueError('record repair source SHA or version differs')
        if receipt['artifacts'] != manifest['artifacts'] or receipt['classification'] != 'validated':
            raise ValueError('record repair inventory or validation differs')
        if version.endswith('-SNAPSHOT'):
            repair_record(receipt, lambda _: None)
            return
        Path('release-notes.md').write_text(release_notes(ROOT / 'CHANGELOG.md', version))
        tag = f'v{version}'
        def update(saved):
            gh('release', 'upload', tag, args.receipt, '--clobber', '--repo', context['repository'])
            command = ['release', 'edit', tag, '--repo', context['repository'], '--draft=false',
                       '--notes-file', 'release-notes.md', '--prerelease=' + str('-' in version).lower()]
            gh(*command)
        repair_record(receipt, update)
    elif args.command == 'full-suite':
        full_suite_record(args, context, manifest)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        sys.exit(1)
