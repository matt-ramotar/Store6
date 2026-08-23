Releasing
========

Store 6 releases follow the cadence defined in [STABILITY.md](./STABILITY.md): monthly
alphas starting at `6.0.0-alpha01`, governed by "cut scope, never cadence". ABI dumps are
committed at every released tag, so any release's surface is diffable from the repository
without resolving artifacts.

The single source of the version is `VERSION_NAME` in [`gradle.properties`](./gradle.properties).
Publication is automated in CI; nothing is uploaded from a local machine.

## Preparing a release

1. Set `VERSION_NAME` in `gradle.properties` to the release version (for example
   `6.0.0-alpha01`), replacing the `-SNAPSHOT` suffix. The root file is the only place
   `VERSION_NAME` exists; module `gradle.properties` files must not reintroduce it, or
   they shadow the root value for that module's publication coordinates.
2. Keep `.github/workflows/store6.yml` in sync: its `klib-publication-check` job publishes
   all modules to Maven Local and then verifies the resulting artifacts against a version
   string hardcoded in the "Verify common and target publications" step. Update that string
   to match the new `VERSION_NAME` in the same change, or the job fails.
3. If the public Kotlin/Java surface changed, refresh the committed
   binary-compatibility-validator dumps under `<module>/api/` (JVM, Android, and klib
   dumps, for example `core/api/jvm/core.api` and `core/api/core.klib.api`) by running the
   module's `apiDump` task. Every module's `apiCheck` runs as part of `./gradlew build`,
   so an unintended ABI change fails before merge. These dumps are committed at every
   released tag (STABILITY.md §7).
4. If the Swift-facing surface changed, regenerate the committed Swift dumps with
   `./gradlew refreshSwiftDumps` and review the diff under `core/api/swift/` and
   `mutations/api/swift/`. `./gradlew checkSwiftDumps` re-verifies them and runs on every
   pull request in the `swift-dumps` job of `.github/workflows/store6.yml`.
5. Update `CHANGELOG.md`.
6. Open a pull request. If it touches documentation sources listed in
   `.github/docs-sync-sources.txt`, add the `docs-sync-ack` label; the `docs-sync-guard`
   job in `.github/workflows/store6.yml` blocks the merge without it.
7. Merge, then tag the release commit and push the tag to
   `MobileNativeFoundation/Store`:
   * `git tag -a vX.Y.Z -m "Version X.Y.Z"`
   * `git push <upstream> vX.Y.Z`

## Publication

Pushing a tag matching `v*` (or triggering the workflow manually via `workflow_dispatch`)
starts the `publish` job in [`.github/workflows/ci.yml`](./.github/workflows/ci.yml),
which:

1. Waits for the full `build-and-test` job to pass.
2. Reads `VERSION_NAME` from `gradle.properties`.
3. Binds the version to the ref: a tag build fails unless the tag is exactly
   `v${VERSION_NAME}` and the version is not a `-SNAPSHOT`; a `workflow_dispatch` build
   fails unless the version is a `-SNAPSHOT` (manual dispatch is the snapshot lane).
   Tag only the merged release commit, so the published SHA is the one the release
   pull request validated.
4. Publishes the shipping artifacts to Maven Central through the Central Portal using
   the Vanniktech Maven Publish plugin wired by the tooling convention plugins:
   `publishToMavenCentral` when the version ends in `-SNAPSHOT`,
   `publishAndReleaseToMavenCentral` (publish plus release) otherwise. The job
   enumerates the published modules explicitly: the ten BOM-constrained artifacts plus
   the BOM itself. Deferred modules stay unpublished until their train; when an
   artifact joins a release, add it to the publish list in `ci.yml`, the BOM
   constraints in `bom/build.gradle.kts`, and STABILITY.md's release column in the
   same change. Credentials come from the workflow secrets.

Artifacts become available once the Central Portal finishes validating and publishing the
deployment. The `v*` tag push also runs the full Store6 matrix
(`.github/workflows/store6.yml`) at the tagged SHA.

## After the release

1. Set `VERSION_NAME` in `gradle.properties` to the next development version and update
   the hardcoded verification version in `store6.yml`'s `klib-publication-check` to match,
   as in step 2 above.
2. State the next alpha's target month in the release notes (STABILITY.md §5).
