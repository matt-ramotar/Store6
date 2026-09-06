import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class ReleaseSimulation(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / '.github/scripts').mkdir(parents=True)
        shutil.copy(ROOT / '.github/scripts/release_control.py', self.root / '.github/scripts')
        shutil.copy(ROOT / '.github/release-manifest.json', self.root / '.github')
        (self.root / 'gradle.properties').write_text('VERSION_NAME=6.0.0-alpha01\n')
        (self.root / 'CHANGELOG.md').write_text('## [6.0.0-alpha01] (2026-09-06)\n\nRelease changes.\n')
        binaries = self.root / 'bin'
        binaries.mkdir()
        self.env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ['PATH'],
                        GITHUB_REPOSITORY='MobileNativeFoundation/Store', GITHUB_EVENT_NAME='push',
                        GITHUB_REF='refs/tags/v6.0.0-alpha01', GITHUB_SHA='a' * 40,
                        GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1', STUB_DIRECTORY=str(self.root))
        needs = {name: {'result': 'success', 'outputs': dict(source_sha='a' * 40, run_id='123',
                                                          run_attempt='1', version='6.0.0-alpha01')} for name in
                 json.loads((ROOT / '.github/release-manifest.json').read_text())['release_jobs']}
        for name in ['release-matrix', 'release-full-suite']:
            needs[name]['outputs'] = dict(source_sha='a' * 40, run_id='123', run_attempt='1', version='6.0.0-alpha01')
        self.env['VALIDATION_NEEDS'] = json.dumps(needs)
        self.executable(binaries / 'git', 'import os\nprint(os.environ["GITHUB_SHA"])\n')
        self.executable(binaries / 'gh', '''import json, os, sys
from pathlib import Path
root = Path(os.environ['STUB_DIRECTORY'])
state = root / 'github-release.json'
operation = sys.argv[2]
if operation == 'view':
    if not state.exists():
        sys.exit(1)
    print(state.read_text())
elif operation == 'create':
    if state.exists():
        sys.exit(1)
    state.write_text(json.dumps(dict(tagName=sys.argv[3], draft=True)))
elif operation == 'upload':
    if os.environ.get('STUB_FAIL_RECORD') == '1':
        sys.exit(1)
    receipt = Path(sys.argv[4])
    (root / 'uploaded-receipt.json').write_text(receipt.read_text())
elif operation == 'edit':
    record = json.loads(state.read_text())
    record['draft'] = False
    state.write_text(json.dumps(record))
else:
    sys.exit(2)
''')
        self.executable(self.root / 'gradlew', '''import os, sys
from pathlib import Path
root = Path(os.environ['STUB_DIRECTORY'])
with (root / 'maven-calls.txt').open('a') as calls:
    calls.write(sys.argv[1] + '\\n')
if os.environ.get('STUB_FAIL_MODULE') and sys.argv[1].startswith(':' + os.environ['STUB_FAIL_MODULE'] + ':'):
    sys.exit(1)
''')

    def executable(self, path, body):
        path.write_text('#!/usr/bin/env python3\n' + body)
        path.chmod(0o755)

    def run_command(self, command, success=True):
        result = subprocess.run(['python3', '.github/scripts/release_control.py', command], cwd=self.root,
                                env=self.env, text=True, capture_output=True)
        if success:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        return result

    def test_complete_publish_then_record_failure_and_idempotent_repair(self):
        for command in ['gate', 'reserve', 'publish']:
            self.run_command(command)
        receipt = (self.root / 'publication-receipt.json').read_bytes()
        self.assertEqual(len((self.root / 'maven-calls.txt').read_text().splitlines()), 11)
        self.env['STUB_FAIL_RECORD'] = '1'
        self.run_command('record', success=False)
        self.assertEqual((self.root / 'publication-receipt.json').read_bytes(), receipt)
        self.run_command('reserve', success=False)
        self.env.pop('STUB_FAIL_RECORD')
        self.run_command('record')
        self.run_command('record')
        self.assertEqual((self.root / 'uploaded-receipt.json').read_bytes(), receipt)
        self.assertFalse(json.loads((self.root / 'github-release.json').read_text())['draft'])
        self.assertEqual(len((self.root / 'maven-calls.txt').read_text().splitlines()), 11)

    def test_partial_maven_success_blocks_release_record_and_automatic_retry(self):
        for command in ['gate', 'reserve']:
            self.run_command(command)
        self.env['STUB_FAIL_MODULE'] = 'testing'
        self.run_command('publish', success=False)
        receipt = json.loads((self.root / 'publication-receipt.json').read_text())
        self.assertEqual(receipt['published_modules'], ['core'])
        self.assertEqual(receipt['attempting_module'], 'testing')
        self.run_command('record', success=False)
        self.run_command('reserve', success=False)
        self.assertEqual(len((self.root / 'maven-calls.txt').read_text().splitlines()), 2)

    def test_snapshot_dispatch_uses_snapshot_tasks_without_github_release(self):
        (self.root / 'gradle.properties').write_text('VERSION_NAME=6.0.0-SNAPSHOT\n')
        self.env.update(GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REF='refs/heads/store6')
        needs = json.loads(self.env['VALIDATION_NEEDS'])
        for job in needs.values():
            job['outputs']['version'] = '6.0.0-SNAPSHOT'
        self.env['VALIDATION_NEEDS'] = json.dumps(needs)
        for command in ['gate', 'reserve', 'publish', 'record']:
            self.run_command(command)
        self.assertFalse((self.root / 'github-release.json').exists())
        calls = (self.root / 'maven-calls.txt').read_text().splitlines()
        self.assertEqual(len(calls), 11)
        self.assertTrue(all(call.endswith(':publishToMavenCentral') for call in calls))

    def test_matrix_and_full_suite_pair_cli_preserve_leaf_attempt_provenance(self):
        manifest = json.loads((self.root / '.github/release-manifest.json').read_text())
        output = self.root / 'job-outputs.txt'
        self.env['GITHUB_OUTPUT'] = str(output)
        for command, required in [('matrix', manifest['matrix_jobs']),
                                  ('full-suite-pair', manifest['full_suite_jobs'])]:
            with self.subTest(command=command):
                needs = {name: dict(result='success', outputs=dict(source_sha='a' * 40, run_id='123',
                                                                  run_attempt='1', version='6.0.0-alpha01'))
                         for name in required}
                self.env['VALIDATION_NEEDS'] = json.dumps(needs)
                self.run_command(command)
                record = json.loads((self.root / 'release-evidence.json').read_text())
                self.assertEqual(record['checks'], needs)
                self.assertIn('version=6.0.0-alpha01\n', output.read_text())
                saved_outputs = output.read_bytes()
                for name in required:
                    with self.subTest(job=name):
                        needs[name]['outputs']['run_attempt'] = '0'
                        self.env['VALIDATION_NEEDS'] = json.dumps(needs)
                        self.run_command(command, success=False)
                        self.assertEqual(output.read_bytes(), saved_outputs)
                        needs[name]['outputs']['run_attempt'] = '1'
        self.assertFalse((self.root / 'maven-calls.txt').exists())


if __name__ == '__main__':
    unittest.main()
