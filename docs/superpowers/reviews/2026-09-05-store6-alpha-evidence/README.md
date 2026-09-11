# Store6 alpha review evidence

This directory supports the [adversarial review](../2026-09-05-store6-alpha-adversarial-review.md) and [parallel revision plan](../../plans/2026-09-05-store6-alpha-revision-plan.md). The integrated review is authoritative for current severity, release scope and disposition. Specialist submissions are supporting analysis, including their rejected hypotheses and intermediate positions; they are not separate release approvals.

## Source authority

- Repository: `matt-ramotar/Store6`.
- Live default/release branch confirmed through GitHub: `store6`.
- Reviewed SHA: `b123c95a373f3629c23e797cb97e2bca18bb260a`.
- Review date: 2026-09-05.
- Isolated source export: `/private/tmp/store6-alpha-review-2026-09-05`.
- [source-manifest.json](source-manifest.json): 792 tracked blobs, zero mismatches, 41 Gradle projects and the full tracked-file inventory.
- The root workspace's stale `main` was preserved. Its initial passing JVM check was excluded from release evidence. No production, tests or configuration were edited by this review.

## Hosted and local evidence

| File | Provenance and interpretation |
| --- | --- |
| [store6-run.json](store6-run.json) | Original push run [33262505687](https://github.com/matt-ramotar/Store6/actions/runs/33262505687), exact reviewed SHA. Six validation jobs succeeded; the PR-only docs guard was skipped. Job/step metadata, not a claim of fresh execution for every task. |
| [ci-run.json](ci-run.json) | Original push run [33262505690](https://github.com/matt-ramotar/Store6/actions/runs/33262505690), exact reviewed SHA. Build-and-test succeeded; publication skipped. |
| [scheduled-run.json](scheduled-run.json) | Scheduled run [33956908960](https://github.com/matt-ramotar/Store6/actions/runs/33956908960), exact reviewed SHA, 2026-09-05. Workflow success. |
| [scheduled-full-jvm.log](scheduled-full-jvm.log) | Retrieved full log from that scheduled run. Line 363 reports `:mutations:jvmTest FROM-CACHE`; line 373 reports a 25-second build; line 392 reports `expected=34 executed=34`. This run restored test output; it did not freshly execute the mutations suite. |
| [local-core-jvm.log](local-core-jvm.log) | Single release-snapshot invocation: `./gradlew :core:jvmTest --stacktrace`. Wrapper ZIP-lock permission failure before task startup. Zero tests executed. No bypass or retry. |
| [artifact-checks.json](artifact-checks.json) | Local artifact integrity, links, finding/task mapping and snapshot comparisons. These are document/source checks, not runtime tests. |

The GitHub connector's commit-workflow lookup returned no runs because that tool filters to PR events. That result was not treated as absence of CI. Read-only GitHub CLI retrieval obtained the push and scheduled results above. The initial sandboxed network read failed; an authorized read-only network call succeeded. No workflow was dispatched or rerun.

## Specialist analysis

| File | Assignment |
| --- | --- |
| [core-review.md](core-review.md) | Core lifecycle/failure channels, testing and extension-probe; coverage limits and counter-evidence. |
| [mutations-review.md](mutations-review.md) | Preparation, parking, cache retention, durable journal/recovery and deferred scheduler integrations. |
| [adapters-review.md](adapters-review.md) | SQLDelight, Room, Compose, Paging, GraphQL, realtime; independent release-finding challenge. |
| [swift-review.md](swift-review.md) | Dynamically assigned Swift bridge review against pinned SKIE source; independent release/cache challenge. |
| [cross-review.md](cross-review.md) | Independent realtime/mutation value-and-metadata, later-invalidation, and projection challenge. |
| [release-review.md](release-review.md) | Initial orchestrator release/distribution notes. Final integrated review corrects consumer-SDK and scope wording from these preliminary notes. |

## Final quality passes

1. **Accuracy:** source snapshot matched its Git blobs; reviewed public contracts and findings' anchors; corrected source-line, consumer-SDK and conditional Swift-severity details through independent challenge.
2. **Warrant:** separated hosted success, cached output, source-proven paths and unexecuted hypotheses; excluded stale-main testing and historical reproduction claims from current release proof.
3. **Reader utility:** mapped every finding/candidate to an accountable lane, added dependency-worktree refresh steps, one Gradle lease, explicit acceptance schedules, source/generated-file ownership and release gates.

Remaining verification limits are stated in the integrated review. The local build restriction prevents fresh runtime regression evidence. No code fix, API dump, publication, UI/browser validation or release action is claimed.
