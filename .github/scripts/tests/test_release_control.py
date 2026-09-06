import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / '.github/scripts/release_control.py'
SPEC = importlib.util.spec_from_file_location('release_control', SCRIPT) if SCRIPT.exists() else None
CONTROL = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC:
    SPEC.loader.exec_module(CONTROL)


class ReleaseFixtures(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(CONTROL, 'The fail-closed release controller does not exist')
        self.sha = 'a' * 40
        self.context = dict(repository='MobileNativeFoundation/Store', event='push',
                            ref='refs/tags/v6.0.0-alpha01', sha=self.sha,
                            checked_out_sha=self.sha, run_id='123', run_attempt='1')
        self.manifest = json.loads((ROOT / '.github/release-manifest.json').read_text())
        self.needs = {name: {'result': 'success', 'outputs': {
            'source_sha': self.sha, 'run_id': '123', 'run_attempt': '1', 'version': '6.0.0-alpha01'}}
                      for name in self.manifest['release_jobs']}
        for name in ['release-matrix', 'release-full-suite']:
            self.needs[name]['outputs'] = {'source_sha': self.sha, 'run_id': '123',
                                          'run_attempt': '1', 'version': '6.0.0-alpha01'}

    def gate(self, version='6.0.0-alpha01'):
        return CONTROL.release_evidence(self.context, version, self.manifest, self.needs)

    def test_valid_tag(self):
        record = self.gate()
        self.assertEqual(record['source_sha'], self.sha)
        self.assertEqual(record['artifacts'], self.manifest['artifacts'])
        self.assertEqual(record['classification'], 'validated')

    def test_mismatched_version(self):
        with self.assertRaisesRegex(ValueError, 'version'):
            self.gate('6.0.0-alpha02')

    def test_snapshot_tag(self):
        self.context['ref'] = 'refs/tags/v6.0.0-SNAPSHOT'
        with self.assertRaisesRegex(ValueError, 'SNAPSHOT'):
            self.gate('6.0.0-SNAPSHOT')

    def test_forbidden_repository(self):
        self.context['repository'] = 'someone/Store'
        with self.assertRaisesRegex(ValueError, 'repository'):
            self.gate()

    def test_missing_matrix(self):
        del self.needs['release-matrix']
        with self.assertRaisesRegex(ValueError, 'missing'):
            self.gate()

    def test_failed_pending_cancelled_skipped_matrix(self):
        for result in ['failure', 'pending', 'cancelled', 'skipped', '']:
            with self.subTest(result=result):
                self.needs['release-matrix']['result'] = result
                with self.assertRaisesRegex(ValueError, 'success'):
                    self.gate()

    def test_wrong_sha_green(self):
        self.needs['release-matrix']['outputs']['source_sha'] = 'b' * 40
        with self.assertRaisesRegex(ValueError, 'SHA'):
            self.gate()

    def test_wrong_run_or_attempt(self):
        for key in ['run_id', 'run_attempt']:
            with self.subTest(key=key):
                changed = copy.deepcopy(self.needs)
                changed['release-full-suite']['outputs'][key] = '99'
                with self.assertRaisesRegex(ValueError, 'provenance'):
                    CONTROL.release_evidence(self.context, '6.0.0-alpha01', self.manifest, changed)

    def test_moved_checkout(self):
        self.context['checked_out_sha'] = 'b' * 40
        with self.assertRaisesRegex(ValueError, 'SHA'):
            self.gate()

    def test_snapshot_dispatch(self):
        self.context.update(event='workflow_dispatch', ref='refs/heads/store6')
        for job in self.needs.values():
            job['outputs']['version'] = '6.0.0-SNAPSHOT'
        self.assertEqual(self.gate('6.0.0-SNAPSHOT')['version'], '6.0.0-SNAPSHOT')

    def test_release_dispatch_denied(self):
        self.context.update(event='workflow_dispatch', ref='refs/heads/store6')
        with self.assertRaisesRegex(ValueError, 'snapshots only'):
            self.gate()

    def test_untrusted_event_denied(self):
        self.context['event'] = 'pull_request'
        with self.assertRaises(ValueError):
            self.gate()

    def test_complete_matrix_required(self):
        needs = {name: {'result': 'success'} for name in self.manifest['matrix_jobs']}
        CONTROL.validate_jobs(needs, self.manifest['matrix_jobs'])
        for name in self.manifest['matrix_jobs']:
            with self.subTest(name=name):
                partial = copy.deepcopy(needs)
                del partial[name]
                with self.assertRaises(ValueError):
                    CONTROL.validate_jobs(partial, self.manifest['matrix_jobs'])

    def test_release_notes_required_before_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            notes = Path(directory) / 'CHANGELOG.md'
            notes.write_text('## [6.0.0-alpha01] (2026-09-06)\n\nConcrete changes.\n\n## [5.0.0]\nOlder.\n')
            self.assertIn('Concrete changes.', CONTROL.release_notes(notes, '6.0.0-alpha01'))
            with self.assertRaises(ValueError):
                CONTROL.release_notes(notes, '6.0.0-alpha02')
            notes.write_text('## [6.0.0-alpha01]\n\nTODO\n')
            with self.assertRaises(ValueError):
                CONTROL.release_notes(notes, '6.0.0-alpha01')

    def test_publish_receipt_survives_record_failure_and_repairs_without_publish(self):
        calls = []
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'publication-receipt.json'
            record = self.gate()
            CONTROL.publish_modules(record, path, lambda task: calls.append(task))
            saved = json.loads(path.read_text())
            self.assertEqual(saved['publication_status'], 'complete')
            with self.assertRaisesRegex(RuntimeError, 'record unavailable'):
                CONTROL.repair_record(saved, lambda _: (_ for _ in ()).throw(RuntimeError('record unavailable')))
            self.assertEqual(json.loads(path.read_text()), saved)
            updates = []
            CONTROL.repair_record(saved, lambda receipt: updates.append(receipt))
            CONTROL.repair_record(saved, lambda receipt: updates.append(receipt))
            self.assertEqual(len(calls), len(self.manifest['artifacts']))
            self.assertEqual(updates[0], updates[1])

    def test_partial_publication_is_not_republished(self):
        calls = []
        def publish(task):
            calls.append(task)
            if len(calls) == 2:
                raise RuntimeError('Central unavailable')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'receipt.json'
            with self.assertRaises(RuntimeError):
                CONTROL.publish_modules(self.gate(), path, publish)
            receipt = json.loads(path.read_text())
            self.assertEqual(receipt['publication_status'], 'partial')
            self.assertEqual(receipt['published_modules'], ['core'])
            with self.assertRaisesRegex(ValueError, 'incomplete'):
                CONTROL.repair_record(receipt, lambda _: self.fail('No release for partial Maven deployment'))
            with self.assertRaisesRegex(ValueError, 'receipt already exists'):
                CONTROL.publish_modules(self.gate(), path, publish)
            self.assertEqual(len(calls), 2)

    def test_repeated_immutable_release_is_blocked_by_existing_record(self):
        with self.assertRaisesRegex(ValueError, 'repair'):
            CONTROL.require_new_release({'tag_name': self.context['ref'].removeprefix('refs/tags/')})
        CONTROL.require_new_release(None)

    def test_root_version_is_unique(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'gradle.properties').write_text('VERSION_NAME=6.0.0-alpha02\n')
            self.assertEqual(CONTROL.root_version(root), '6.0.0-alpha02')
            (root / 'core').mkdir()
            (root / 'core/gradle.properties').write_text('VERSION_NAME=6.0.0-SNAPSHOT\n')
            with self.assertRaisesRegex(ValueError, 'override'):
                CONTROL.root_version(root)

    def test_publication_metadata_versions_and_bom_roster(self):
        self.assertTrue(hasattr(CONTROL, 'publication_versions'), 'Publication metadata must use the root version')
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            for module in ['core', 'bom']:
                target = repository / module / '6.0.0-alpha01'
                target.mkdir(parents=True)
                dependencies = ('<dependencyManagement><dependencies><dependency><groupId>org.example</groupId>'
                                '<artifactId>core</artifactId><version>6.0.0-alpha01</version></dependency>'
                                '</dependencies></dependencyManagement>') if module == 'bom' else ''
                (target / f'{module}-6.0.0-alpha01.pom').write_text(
                    '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>org.example</groupId>'
                    f'<artifactId>{module}</artifactId><version>6.0.0-alpha01</version>{dependencies}</project>')
                (target / f'{module}-6.0.0-alpha01.module').write_text(json.dumps(dict(
                    component=dict(group='org.example', module=module, version='6.0.0-alpha01'), variants=[])))
            CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core', 'bom'], ['core', 'bom'],
                                         expected_publications=['core', 'bom'])
            pom = repository / 'bom/6.0.0-alpha01/bom-6.0.0-alpha01.pom'
            pom.write_text(pom.read_text().replace('<version>6.0.0-alpha01</version></dependency>',
                                                 '<version>6.0.0-SNAPSHOT</version></dependency>'))
            with self.assertRaisesRegex(ValueError, 'version'):
                CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core', 'bom'], ['core', 'bom'],
                                             expected_publications=['core', 'bom'])

    def test_full_suite_rejects_cached_up_to_date_and_missing_task(self):
        for outcome in [' FROM-CACHE', ' UP-TO-DATE', ' SKIPPED', ' NO-SOURCE']:
            with self.subTest(outcome=outcome):
                with self.assertRaisesRegex(ValueError, 'executed'):
                    CONTROL.task_outcome('> Task :mutations:jvmTest' + outcome + '\n')
        with self.assertRaises(ValueError):
            CONTROL.task_outcome('BUILD SUCCESSFUL\n')
        self.assertEqual(CONTROL.task_outcome('> Task :mutations:jvmTest\n'), 'executed')
        self.assertEqual(CONTROL.task_outcome('> Task :mutations:jvmTest FAILED\n'), 'failed')

    def test_full_suite_task_names_and_repeated_failed_outcome(self):
        neighboring_tasks = (
            '> Task :mutations:jvmTestProcessResources NO-SOURCE\n'
            '> Task :mutations:jvmTestClasses\n'
        )
        observed_failure = (
            neighboring_tasks + '> Task :mutations:jvmTest\n'
            '284 tests completed, 6 failed\n'
            '> Task :mutations:jvmTest FAILED\n'
            'BUILD FAILED in 7s\n'
        )
        self.assertEqual(CONTROL.task_outcome(observed_failure), 'failed')
        self.assertEqual(CONTROL.task_outcome(neighboring_tasks + '> Task :mutations:jvmTest\n'), 'executed')
        self.assertEqual(CONTROL.task_outcome(neighboring_tasks + '> Task :mutations:jvmTest FAILED\n'), 'failed')
        self.assertEqual(CONTROL.task_outcome('> Task :mutations:jvmTest\r\n'), 'executed')
        self.assertEqual(CONTROL.task_outcome('> Task :mutations:jvmTest FAILED\n' * 2), 'failed')
        with self.assertRaisesRegex(ValueError, 'executed'):
            CONTROL.task_outcome(neighboring_tasks)
        for outcome in ['FROM-CACHE', 'UP-TO-DATE', 'SKIPPED', 'NO-SOURCE']:
            with self.subTest(outcome=outcome):
                for started in ['', '> Task :mutations:jvmTest\n']:
                    with self.subTest(started=bool(started)):
                        with self.assertRaisesRegex(ValueError, 'executed'):
                            CONTROL.task_outcome(neighboring_tasks + started + '> Task :mutations:jvmTest ' + outcome + '\n')

    def test_full_suite_identifiers_and_skipped_class_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            results = Path(directory)
            xml = results / 'TEST-example.Test.xml'
            xml.write_text('<testsuite><testcase classname="example.Test" name="works"/></testsuite>')
            record = CONTROL.test_identifiers(results, ['example.Test'])
            self.assertEqual(record[0]['id'], 'example.Test#works')
            xml.write_text('<testsuite><testcase classname="example.Test" name="works"><skipped/></testcase></testsuite>')
            with self.assertRaisesRegex(ValueError, 'executed'):
                CONTROL.test_identifiers(results, ['example.Test'])

    def test_every_release_job_requires_current_source_run_attempt_and_version(self):
        for name in self.manifest['release_jobs']:
            for key in ['source_sha', 'run_id', 'run_attempt', 'version']:
                for mismatch in [None, 'different']:
                    with self.subTest(job=name, field=key, mismatch=mismatch):
                        needs = copy.deepcopy(self.needs)
                        if mismatch is None:
                            needs[name]['outputs'].pop(key)
                        else:
                            needs[name]['outputs'][key] = mismatch
                        with self.assertRaises(ValueError):
                            CONTROL.release_evidence(self.context, '6.0.0-alpha01', self.manifest, needs)

    def test_matrix_and_full_suite_pair_require_each_leaf_provenance(self):
        validate = getattr(CONTROL, 'validate_job_provenance', None)
        self.assertIsNotNone(validate, 'Required leaf jobs need individual provenance validation')
        for required in [self.manifest['matrix_jobs'], ['first-execution', 'second-execution']]:
            needs = {name: copy.deepcopy(self.needs['build-and-test']) for name in required}
            validate(self.context, '6.0.0-alpha01', needs, required)
            for name in required:
                with self.subTest(job=name):
                    changed = copy.deepcopy(needs)
                    changed[name]['outputs']['run_attempt'] = '0'
                    with self.assertRaisesRegex(ValueError, 'provenance'):
                        validate(self.context, '6.0.0-alpha01', changed, required)

    def test_full_suite_second_attempt_cannot_relabel_first_execution(self):
        validate = getattr(CONTROL, 'validate_job_provenance', None)
        self.assertIsNotNone(validate, 'The full-suite pair must compare both execution attempts')
        context = dict(self.context, run_attempt='2')
        required = ['first-execution', 'second-execution']
        needs = {name: copy.deepcopy(self.needs['build-and-test']) for name in required}
        needs['second-execution']['outputs']['run_attempt'] = '2'
        with self.assertRaisesRegex(ValueError, 'first-execution.*provenance'):
            validate(context, '6.0.0-alpha01', needs, required)

    def test_publication_metadata_requires_an_explicit_target_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.write_publication(repository, 'core')
            with self.assertRaisesRegex(ValueError, 'inventory'):
                CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'])

    def test_expected_target_requires_its_own_pom_and_module_metadata(self):
        for extension in ['pom', 'module']:
            with self.subTest(extension=extension), tempfile.TemporaryDirectory() as directory:
                repository = Path(directory)
                self.write_publication(repository, 'core')
                target = self.write_publication(repository, 'core-jvm')
                (target / 'core-jvm-6.0.0-alpha01.jar').write_bytes(b'publication fixture')
                (target / f'core-jvm-6.0.0-alpha01.{extension}').unlink()
                with self.assertRaisesRegex(ValueError, 'missing.*core-jvm'):
                    CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                                 expected_publications=['core', 'core-jvm'])

    def test_complete_expected_publication_inventory_is_validated_once(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            for name in ['mutations', 'mutations-testing', 'mutations-jvm', 'mutations-testing-jvm']:
                self.write_publication(repository, name)
            expected = ['mutations', 'mutations-testing', 'mutations-jvm', 'mutations-testing-jvm']
            count = CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example',
                                                 ['mutations', 'mutations-testing'], ['mutations', 'mutations-testing'],
                                                 expected_publications=expected)
            self.assertEqual(count, len(expected))

    def test_unlisted_target_cannot_escape_publication_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self.write_publication(repository, 'core')
            self.write_publication(repository, 'core-jvm')
            with self.assertRaisesRegex(ValueError, 'unexpected.*core-jvm'):
                CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                             expected_publications=['core'])

    def test_generated_kmp_targets_link_to_the_owning_root_component(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            expected = self.write_kmp_publications(repository)
            count = CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example',
                                                 ['core'], ['core'], expected_publications=expected)
            self.assertEqual(count, 4)

    def test_kmp_component_owner_uses_the_longest_module_name(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            expected = self.write_kmp_publications(repository, 'mutations')
            expected += self.write_kmp_publications(repository, 'mutations-testing')
            modules = ['mutations', 'mutations-testing']
            self.assertEqual(CONTROL.publication_versions(
                repository, '6.0.0-alpha01', 'org.example', modules, modules,
                expected_publications=expected), 8)
            metadata = repository / 'mutations-testing-jvm/6.0.0-alpha01/mutations-testing-jvm-6.0.0-alpha01.module'
            data = json.loads(metadata.read_text())
            data['component'].update(module='mutations', url='../../mutations/6.0.0-alpha01/mutations-6.0.0-alpha01.module')
            metadata.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', modules, modules,
                                             expected_publications=expected)

    def test_kmp_target_component_rejects_wrong_coordinates(self):
        for field, value in [('module', 'unrelated'), ('group', 'org.foreign'), ('version', '6.0.0-SNAPSHOT')]:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                repository = Path(directory)
                expected = self.write_kmp_publications(repository)
                metadata = repository / 'core-jvm/6.0.0-alpha01/core-jvm-6.0.0-alpha01.module'
                data = json.loads(metadata.read_text())
                data['component'][field] = value
                metadata.write_text(json.dumps(data))
                with self.assertRaises(ValueError):
                    CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                                 expected_publications=expected)

    def test_kmp_target_component_rejects_missing_or_forged_urls(self):
        urls = [None, '', 7, '../../core/6.0.0-alpha01/core-jvm-6.0.0-alpha01.module',
                '../../other/6.0.0-alpha01/other-6.0.0-alpha01.module',
                '../../core/6.0.0-SNAPSHOT/core-6.0.0-SNAPSHOT.module',
                '../../../outside/core-6.0.0-alpha01.module',
                '../../%63ore/6.0.0-alpha01/core-6.0.0-alpha01.module',
                'https://example.invalid/core-6.0.0-alpha01.module',
                '/core/6.0.0-alpha01/core-6.0.0-alpha01.module',
                '../../core/6.0.0-alpha01/core-6.0.0-alpha01.module?forged=1']
        for url in urls:
            with self.subTest(url=url), tempfile.TemporaryDirectory() as directory:
                repository = Path(directory)
                expected = self.write_kmp_publications(repository)
                metadata = repository / 'core-jvm/6.0.0-alpha01/core-jvm-6.0.0-alpha01.module'
                data = json.loads(metadata.read_text())
                if url is None:
                    data['component'].pop('url')
                else:
                    data['component']['url'] = url
                metadata.write_text(json.dumps(data))
                with self.assertRaises(ValueError):
                    CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                                 expected_publications=expected)

    def test_component_url_cannot_disguise_a_self_component(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            target = self.write_publication(repository, 'core')
            metadata = target / 'core-6.0.0-alpha01.module'
            data = json.loads(metadata.read_text())
            data['component']['url'] = '../../unrelated/6.0.0-alpha01/unrelated-6.0.0-alpha01.module'
            metadata.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                             expected_publications=['core'])

    def test_kmp_component_url_cannot_follow_a_symlink_outside_repository(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory) / 'repository'
            expected = self.write_kmp_publications(repository)
            metadata = repository / 'core/6.0.0-alpha01/core-6.0.0-alpha01.module'
            outside = Path(directory) / 'outside.module'
            outside.write_bytes(metadata.read_bytes())
            metadata.unlink()
            metadata.symlink_to(outside)
            with self.assertRaises(ValueError):
                CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                             expected_publications=expected)

    def test_kmp_link_preserves_root_and_target_pom_and_metadata_requirements(self):
        for artifact in ['core', 'core-jvm']:
            for extension in ['pom', 'module']:
                with self.subTest(artifact=artifact, extension=extension), tempfile.TemporaryDirectory() as directory:
                    repository = Path(directory)
                    expected = self.write_kmp_publications(repository)
                    (repository / artifact / '6.0.0-alpha01' / f'{artifact}-6.0.0-alpha01.{extension}').unlink()
                    with self.assertRaisesRegex(ValueError, 'missing'):
                        CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                                     expected_publications=expected)

    def test_kmp_link_preserves_internal_metadata_version_checks(self):
        for field in ['dependencies', 'dependencyConstraints', 'available-at']:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                repository = Path(directory)
                expected = self.write_kmp_publications(repository)
                metadata = repository / 'core-jvm/6.0.0-alpha01/core-jvm-6.0.0-alpha01.module'
                data = json.loads(metadata.read_text())
                dependency = dict(group='org.example', module='core', version=dict(requires='6.0.0-SNAPSHOT'))
                data['variants'][0][field] = (dict(dependency, version='6.0.0-SNAPSHOT')
                                            if field == 'available-at' else [dependency])
                metadata.write_text(json.dumps(data))
                with self.assertRaisesRegex(ValueError, 'version'):
                    CONTROL.publication_versions(repository, '6.0.0-alpha01', 'org.example', ['core'], ['core'],
                                                 expected_publications=expected)

    def write_kmp_publications(self, repository, owner='core'):
        version = '6.0.0-alpha01'
        expected = [owner] + [owner + suffix for suffix in ['-android', '-jvm', '-iosarm64']]
        root = self.write_publication(repository, owner)
        root_metadata = root / f'{owner}-{version}.module'
        root_data = dict(formatVersion='1.1', component=dict(
            group='org.example', module=owner, version=version, attributes={'org.gradle.status': 'release'}), variants=[])
        for artifact, extension in zip(expected[1:], ['aar', 'jar', 'klib']):
            target = self.write_publication(repository, artifact)
            metadata = target / f'{artifact}-{version}.module'
            filename = f'{artifact}-{version}.{extension}'
            (target / filename).write_bytes(b'publication fixture')
            target_data = dict(formatVersion='1.1', component=dict(
                url=f'../../{owner}/{version}/{owner}-{version}.module', group='org.example',
                module=owner, version=version, attributes={'org.gradle.status': 'release'}), variants=[dict(
                    name='apiElements-published', files=[dict(name=filename, url=filename)],
                    dependencies=[dict(group='org.example', module=owner, version=dict(requires=version))])])
            metadata.write_text(json.dumps(target_data))
            root_data['variants'].append({'name': artifact, 'available-at': dict(
                url=f'../../{artifact}/{version}/{artifact}-{version}.module',
                group='org.example', module=artifact, version=version)})
        root_metadata.write_text(json.dumps(root_data))
        return expected

    def write_publication(self, repository, artifact):
        target = repository / artifact / '6.0.0-alpha01'
        target.mkdir(parents=True)
        (target / f'{artifact}-6.0.0-alpha01.pom').write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>org.example</groupId>'
            f'<artifactId>{artifact}</artifactId><version>6.0.0-alpha01</version></project>')
        (target / f'{artifact}-6.0.0-alpha01.module').write_text(json.dumps(dict(
            component=dict(group='org.example', module=artifact, version='6.0.0-alpha01'), variants=[])))
        return target



class FullSuiteInstrumentationFixtures(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'gradle.properties').write_text('VERSION_NAME=6.0.0-alpha01\n')
        source = self.root / 'mutations/src/jvmTest/kotlin/ExampleTest.kt'
        source.parent.mkdir(parents=True)
        source.write_text('package example\nclass ExampleTest\n')
        self.results = self.root / 'results'
        self.results.mkdir()
        self.xml = self.results / 'TEST-example.ExampleTest.xml'
        self.log = self.root / 'gradle.log'
        self.output = self.root / 'execution.json'
        self.context = dict(sha='a' * 40, checked_out_sha='a' * 40,
                            repository='example/repository', run_id='123', run_attempt='1')
        root_patch = mock.patch.object(CONTROL, 'ROOT', self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        output_patch = mock.patch.object(CONTROL, 'append_outputs')
        self.append_outputs = output_patch.start()
        self.addCleanup(output_patch.stop)
        summary_patch = mock.patch.dict(CONTROL.os.environ, {'GITHUB_STEP_SUMMARY': ''})
        summary_patch.start()
        self.addCleanup(summary_patch.stop)
        self.write_inputs()

    def write_inputs(self, log_extra='', stderr='', failure=False, name='works', cdata=False):
        suite = ET.Element('testsuite', tests='1', failures='1' if failure else '0', errors='0', skipped='0')
        testcase = ET.SubElement(suite, 'testcase', classname='example.ExampleTest', name=name)
        if failure:
            ET.SubElement(testcase, 'failure', message='original assertion').text = 'original assertion'
        error_stream = ET.SubElement(suite, 'system-err')
        error_stream.text = stderr
        xml = ET.tostring(suite, encoding='unicode')
        if cdata:
            xml = xml.replace('<system-err>' + stderr + '</system-err>',
                              '<system-err><![CDATA[' + stderr + ']]></system-err>')
        self.xml.write_text(xml)
        self.log.write_text('> Task :mutations:jvmTest' + (' FAILED' if failure else '') + '\n' + log_extra)

    def record(self, exit_code=0):
        args = SimpleNamespace(output=str(self.output), log=str(self.log), results=str(self.results),
                               sequence=1, exit_code=exit_code)
        CONTROL.full_suite_record(args, self.context, {})

    def saved(self):
        record = json.loads(self.output.read_text())
        self.assertEqual(record['expected_classes'], ['example.ExampleTest'])
        self.assertEqual(len(record['test_identifiers']), 1)
        self.assertIn(self.xml.name, record['xml_files'])
        return record

    def test_green_xml_and_successful_task_reject_log_transform_failure(self):
        self.write_inputs(log_extra='  Unable to transform org/example/Record$Nested\n')
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record()
        record = self.saved()
        self.assertEqual(record['classification'], 'infrastructure-or-incomplete')
        self.assertEqual(record['test_identifiers'][0]['outcome'], 'passed')
        self.assertEqual(record['instrumentation_failures'][0]['class_name'], 'org/example/Record$Nested')
        self.assertEqual(record['instrumentation_failures'][0]['source'], 'gradle-log')
        self.assertEqual(record['instrumentation_failures'][0]['line'], 2)
        self.append_outputs.assert_not_called()

    def test_green_xml_rejects_decoded_stderr_and_cdata_transform_failures(self):
        for cdata in [False, True]:
            with self.subTest(cdata=cdata):
                self.write_inputs(stderr='\n\tUnable to transform org/example/Record$Nested\n', cdata=cdata)
                if not cdata:
                    self.xml.write_text(self.xml.read_text().replace('transform', 'trans&#102;orm'))
                with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
                    self.record()
                record = self.saved()
                self.assertEqual(record['classification'], 'infrastructure-or-incomplete')
                marker = record['instrumentation_failures'][0]
                self.assertEqual(marker['class_name'], 'org/example/Record$Nested')
                self.assertEqual(marker['source'], 'xml-system-err')
                self.assertEqual(marker['path'], self.xml.name)
                self.assertEqual(marker['line'], 2)
                self.append_outputs.assert_not_called()

    def test_real_test_failure_keeps_classification_and_testcase_with_marker(self):
        self.write_inputs(stderr='Unable to transform org/example/Record\n', failure=True)
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record(exit_code=1)
        record = self.saved()
        self.assertEqual(record['classification'], 'test-failure')
        self.assertEqual(record['test_identifiers'], [{'id': 'example.ExampleTest#works', 'outcome': 'failed'}])
        self.assertIn('instrumentation_failures', record)
        self.assertEqual(record['instrumentation_failures'][0]['class_name'], 'org/example/Record')
        self.assertEqual(ET.parse(self.xml).find('testcase/failure').get('message'), 'original assertion')
        self.append_outputs.assert_not_called()

    def test_log_marker_preserves_legal_jvm_class_name_characters(self):
        names = ["org/example/Foo'Bar", 'org/example/Foo"Bar', 'org/example/Foo`Bar',
                 'org/example/Foo Bar', '"org/example/QuotedClass"',
                 'org/example/Name extra words', ' Leading', 'Trailing ', ' \t ']
        for class_name in names:
            with self.subTest(class_name=class_name):
                self.write_inputs(log_extra='Unable to transform ' + class_name + '\n')
                with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
                    self.record()
                record = self.saved()
                self.assertEqual(record['classification'], 'infrastructure-or-incomplete')
                self.assertEqual(record['instrumentation_failures'][0]['class_name'], class_name)
                self.append_outputs.assert_not_called()

    def test_cdata_stderr_marker_preserves_legal_jvm_class_name_characters(self):
        names = ["org/example/Foo'Bar", 'org/example/Foo"Bar', 'org/example/Foo`Bar',
                 'org/example/Foo Bar', '"org/example/QuotedClass"',
                 'org/example/Name extra words', ' Leading', 'Trailing ', ' \t ']
        for class_name in names:
            with self.subTest(class_name=class_name):
                self.write_inputs(stderr='Unable to transform ' + class_name + '\n', cdata=True)
                with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
                    self.record()
                record = self.saved()
                self.assertEqual(record['classification'], 'infrastructure-or-incomplete')
                self.assertEqual(record['instrumentation_failures'][0]['class_name'], class_name)
                self.append_outputs.assert_not_called()

    def test_log_marker_records_actual_supplied_log_path(self):
        self.log = self.root / 'captured-worker-output.log'
        self.write_inputs(log_extra='Unable to transform org/example/Record\n')
        with self.assertRaisesRegex(ValueError, 'full-suite evidence did not pass'):
            self.record()
        self.assertEqual(self.saved()['instrumentation_failures'][0]['path'], str(self.log))
        self.append_outputs.assert_not_called()

    def test_clean_passing_execution_still_emits_provenance(self):
        self.record()
        self.assertEqual(self.saved()['classification'], 'passed')
        self.append_outputs.assert_called_once_with(self.context, '6.0.0-alpha01')

    def test_mentions_quotes_and_test_names_do_not_imply_transform_failure(self):
        mentions = (
            'ExampleTest > Unable to transform org/example/TestName PASSED\n'
            'An example says Unable to transform org/example/Prose\n'
            '"Unable to transform org/example/Quoted"\n'
            "'Unable to transform org/example/Quoted'\n"
        )
        self.write_inputs(log_extra=mentions, stderr=mentions,
                          name='Unable to transform org/example/TestName')
        self.record()
        self.assertEqual(self.saved()['classification'], 'passed')
        self.append_outputs.assert_called_once_with(self.context, '6.0.0-alpha01')

if __name__ == '__main__':
    unittest.main()
