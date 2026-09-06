import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]


class WorkflowContract(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((ROOT / '.github/release-manifest.json').read_text())
        self.ci = (ROOT / '.github/workflows/ci.yml').read_text()
        self.matrix = (ROOT / '.github/workflows/store6.yml').read_text()
        self.full = (ROOT / '.github/workflows/store6-full-jvm.yml').read_text()

    def test_complete_reusable_matrix_has_no_missing_job(self):
        self.assertIn('workflow_call:', self.matrix)
        jobs = set(re.findall(r'^  ([\w-]+):\n(?=    (?:if:|runs-on:))', self.matrix, re.MULTILINE))
        self.assertEqual(jobs - {'docs-sync-guard', 'validation-evidence'}, set(self.manifest['matrix_jobs']))
        receipt = self.matrix.split('  validation-evidence:', 1)[1]
        needs = re.search(r'needs: \[(.*?)\]', receipt)[1].split(', ')
        self.assertEqual(set(needs), set(self.manifest['matrix_jobs']))

    def test_publication_waits_for_same_run_validation(self):
        publish = self.ci.split('  publish:', 1)[1]
        needs = re.search(r'needs: \[(.*?)\]', publish)
        self.assertIsNotNone(needs, 'Publication must list every required validation prerequisite')
        self.assertEqual(set(needs[1].split(', ')), set(self.manifest['release_jobs']))
        self.assertIn('uses: ./.github/workflows/store6.yml', self.ci)
        self.assertIn('uses: ./.github/workflows/store6-full-jvm.yml', self.ci)
        self.assertLess(publish.index('release_control.py gate'), publish.index('release_control.py reserve'))
        self.assertLess(publish.index('release_control.py reserve'), publish.index('release_control.py publish'))
        self.assertLess(publish.index('release_control.py publish'), publish.index('publication-receipt-${{'))
        self.assertLess(publish.index('publication-receipt-${{'), publish.index('release_control.py record'))

    def test_full_suite_second_run_requires_first_success(self):
        self.assertIn('workflow_call:', self.full)
        self.assertIn('needs: first-execution', self.full)
        self.assertEqual(self.full.count('uses: ./.github/workflows/store6-full-jvm-run.yml'), 2)
        config = (ROOT / 'mutations/build.gradle.kts').read_text()
        self.assertIn('outputs.upToDateWhen { false }', config)
        self.assertIn('outputs.doNotCacheIf(', config)
        runner = ROOT / '.github/workflows/store6-full-jvm-run.yml'
        self.assertTrue(runner.exists(), 'Full-suite execution workflow must exist')
        run = runner.read_text()
        self.assertIn('--console=plain', run)
        self.assertIn('release_control.py full-suite', run)
        self.assertIn('if: ${{ always() }}', run)
        self.assertNotIn('docs/v6', run + self.full)
        self.assertNotIn('gh issue', run + self.full)

    def test_publication_roster_matches_bom_and_root_version(self):
        bom = (ROOT / 'bom/build.gradle.kts').read_text()
        constraints = re.findall(r'api\("\$group:([^:]+):\$version"\)', bom)
        self.assertEqual(set(constraints), set(self.manifest['artifacts']) - {'bom'})
        self.assertNotIn('version="6.0.0-SNAPSHOT"', self.matrix)
        self.assertIn('release_control.py version', self.matrix)
        self.assertIn('release_control.py publication-versions', self.matrix)
        rows = re.findall(r'^\| `([^`]+)` \|.*\| (alpha01[^|]*)\|$',
                          (ROOT / 'STABILITY.md').read_text(), re.MULTILINE)
        self.assertEqual({module for module, _ in rows}, set(self.manifest['artifacts']))

    def test_record_repair_cannot_invoke_maven(self):
        path = ROOT / '.github/workflows/store6-release-record.yml'
        self.assertTrue(path.exists(), 'An independent record-repair workflow must exist')
        repair = path.read_text()
        self.assertIn('release_control.py record', repair)
        self.assertIn('actions/download-artifact@v4', repair)
        self.assertNotIn('./gradlew', repair)
        self.assertNotIn('release_control.py publish', repair)

    def test_every_validation_leaf_emits_checked_source_provenance(self):
        for workflow, names in [(self.ci, ['build-and-test', 'workflow-fixtures']),
                                (self.matrix, self.manifest['matrix_jobs'])]:
            for name in names:
                with self.subTest(job=name):
                    block = re.split(r'\n  (?=\S)', workflow.split(f'  {name}:\n', 1)[1], maxsplit=1)[0]
                    for field in ['source_sha', 'run_id', 'run_attempt', 'version']:
                        self.assertIn(f'{field}: ${{{{ steps.provenance.outputs.{field} }}}}', block)
                    self.assertIn('release_control.py provenance', block)

    def test_full_suite_pair_aggregates_both_executions(self):
        self.assertIn('needs: [first-execution, second-execution]', self.full)
        self.assertIn('release_control.py full-suite-pair', self.full)
        self.assertEqual(self.manifest.get('full_suite_jobs'), ['first-execution', 'second-execution'])
        for field in ['source_sha', 'run_id', 'run_attempt', 'version']:
            self.assertIn(f'value: ${{{{ jobs.validation-evidence.outputs.{field} }}}}', self.full)

    def test_publication_verification_passes_each_expected_target(self):
        self.assertIn('publications+=("${artifact_id}")', self.matrix)
        self.assertIn('--publications "${publications[@]}"', self.matrix)


if __name__ == '__main__':
    unittest.main()
