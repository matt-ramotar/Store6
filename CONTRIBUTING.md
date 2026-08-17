# Contributing to Store

The canonical repository is [MobileNativeFoundation/Store](https://github.com/MobileNativeFoundation/Store). Fork that repository, open issues and pull requests there, and treat its `main` branch as the public source of record.

## Security reports

If you believe you have found a vulnerability, use GitHub private vulnerability reporting:

<https://github.com/MobileNativeFoundation/Store/security/advisories/new>

Do not file a public issue. See [SECURITY.md](SECURITY.md). This repository does not publish a security-report email.

## Support versus bugs

| Need | Where |
| --- | --- |
| Usage question or help | Kotlin Slack [#store](https://kotlinlang.slack.com/archives/C06007Z01HU) |
| Defect in published or in-tree behavior | [Bug report](https://github.com/MobileNativeFoundation/Store/issues/new?template=bug_report.md) (`.github/ISSUE_TEMPLATE/bug_report.md`) |
| Feature idea | [Feature request](https://github.com/MobileNativeFoundation/Store/issues/new?template=feature_request.md) (`.github/ISSUE_TEMPLATE/feature_request.md`) |
| API change | [Proposal](https://github.com/MobileNativeFoundation/Store/issues/new?template=proposal.md) (`.github/ISSUE_TEMPLATE/proposal.md`) |
| Vulnerability | [Private vulnerability reporting](https://github.com/MobileNativeFoundation/Store/security/advisories/new), not a public issue |

Search [existing issues](https://github.com/MobileNativeFoundation/Store/issues) before opening a new one. Slack is not a vulnerability channel, a conduct-reporting channel, or a substitute for a durable bug report.

## Prerequisites

Hosted pull-request workflows install **Azul Zulu JDK 17** (`actions/setup-java@v4`, `java-version: '17'`, `distribution: 'zulu'`). Use a JDK 17 to run the wrapper locally.

Use the repository Gradle wrapper. Do not substitute another Gradle install. The wrapper is Gradle **8.11.1** (`gradle/wrapper/gradle-wrapper.properties`). On Unix that is `./gradlew`. On Windows that is `gradlew.bat`. Hosted CI runs only on `ubuntu-latest` and `macos-latest`.

The version catalog pins Kotlin **2.3.20** (`gradle/libs.versions.toml`, `baseKotlin`). The wrapper resolves that compiler. You do not install a separate Kotlin distribution.

JVM compile target is 11 (`build.gradle.kts`). That is the bytecode target, not the JDK used to run Gradle.

ktlint and Spotless are applied in `build.gradle.kts` only to subprojects whose names do **not** start with `store6`. `store6-*` modules are excluded from that block. Pull-request workflows do not run a separate formatting job.

## Verify your change

No single local Gradle command is equivalent to hosted CI. Pull requests on `main` always run [CI](.github/workflows/ci.yml) and [Store6](.github/workflows/store6.yml). Those two workflows use different operating systems, module sets, and extra scripted checks. [Store6 Benchmarks](.github/workflows/store6-benchmarks.yml) also runs on pull requests that touch `store6-benchmarks/**` or its workflow file. It is report-only. Match the jobs that cover the modules you changed. The workflow files are the complete step list.

### `CI` workflow (`.github/workflows/ci.yml`, `ubuntu-latest`)

```text
./gradlew clean build koverXmlReport --stacktrace
```

`settings.gradle` includes Store 5 modules (`:store`, `:cache`, `:multicast`, `:rx2`, `:core`) and the `store6-*` modules. Default `store6-mutations` `jvmTest` excludes `org.mobilenativefoundation.store6.mutations.MutationJournalLincheckTest` unless `-Pstore6.fullJvmSuite` is set (`store6-mutations/build.gradle.kts`). A census step then requires that default `jvmTest` executed exactly the non-Lincheck suites.

This command is the `CI` workflow's Gradle invocation. It is not the Store6 Linux job, the Apple jobs, the JS lock-discipline canary, the klib publication check, or native stress.

### Store6 workflow (`.github/workflows/store6.yml`)

| Job | Runner | What it runs |
| --- | --- | --- |
| `docs-sync-guard` | `ubuntu-latest`, pull requests only | Fails if the PR edits a path listed in [`.github/docs-sync-sources.txt`](.github/docs-sync-sources.txt) without the `docs-sync-ack` label |
| `linux-build-test` | `ubuntu-latest` | Per-module `build` / `run` / `jsNodeTest` steps, plus scripted checks |
| `apple-tests` | `macos-latest` | `iosSimulatorArm64Test` and `macosArm64Test` for the Store6 modules listed in the workflow, after selecting an available iPhone simulator, then `:store6-devtools-demo:linkDebugFrameworkIosSimulatorArm64` |
| `swift-dumps` | `macos-latest` | `./gradlew checkSwiftDumps --stacktrace` |
| `klib-publication-check` | `ubuntu-latest` | Unsigned `publishToMavenLocal` for the listed Store6 modules, then an artifact census at `6.0.0-SNAPSHOT` |
| `native-stress` | `macos-latest` | `:store6-core:macosArm64Test` filtered to `StoreEvictionStressTest`, `StoreInvalidationStressTest`, `StoreCloseLifecycleTest`, and `StoreBackpressureConformanceTest` |

Linux Store6 `./gradlew` steps observed in that workflow include:

```text
./gradlew :store6-core:build :store6-testing:build :store6-sqldelight:build -Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true --stacktrace
./gradlew :store6-quickstart:run --stacktrace
./gradlew :store6-sqldelight-sample:run --stacktrace
./gradlew :store6-extension-probe:build -Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true --stacktrace
./gradlew :store6-compose:build :store6-compose-demo:build -Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true --stacktrace
./gradlew :store6-room:build -Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true --stacktrace
./gradlew :store6-room-sample:run --stacktrace
./gradlew :store6-benchmarks:build --stacktrace
./gradlew :store6-devtools:build :store6-devtools-inspector:build :store6-devtools-demo:build -Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true --stacktrace
./gradlew :store6-mutations:build -Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true --stacktrace
./gradlew :store6-mutations-quickstart:run --stacktrace
./gradlew :store6-mutations-testing:build :store6-mutations-sqldelight:build -Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true --stacktrace
./gradlew :store6-paging-androidx:build -Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true --stacktrace
./gradlew :store6-paging-androidx-sample:run --stacktrace
./gradlew :store6-graphql:build -Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true --stacktrace
./gradlew :store6-graphql-sample:run --stacktrace
./gradlew :store6-realtime:build -Pkotlin.native.enableKlibsCrossCompilation=true -Pkotlin.apple.xcodeCompatibility.nowarn=true --stacktrace
./gradlew :store6-realtime-sample:run --stacktrace
./gradlew :store6-core:jsNodeTest :store6-testing:jsNodeTest :store6-mutations:jsNodeTest :store6-mutations-testing:jsNodeTest :store6-paging-androidx:jsNodeTest :store6-graphql:jsNodeTest :store6-realtime:jsNodeTest --stacktrace
```

The same Linux job also runs non-Gradle checks (Compose stability report, mutations non-Lincheck census, extension internal-access grep, seam-package freeze, primitive whitelist). Those steps are in `.github/workflows/store6.yml`. They are not implied by `build` alone.

Apple, Swift-dump, and native-stress jobs have no Linux equivalent in these workflows.

### Not default pull-request CI

| Command or job | Where it runs | Why it is not a local stand-in for PR CI |
| --- | --- | --- |
| `./gradlew :store6-mutations:jvmTest -Pstore6.fullJvmSuite --stacktrace` | [Store6 full mutations JVM suite](.github/workflows/store6-full-jvm.yml), scheduled / `workflow_dispatch` | Includes Lincheck. Default `jvmTest` and the `CI` / Store6 Linux jobs exclude it. |
| `./gradlew :store6-benchmarks:smokeBenchmark --stacktrace` | [Store6 Benchmarks](.github/workflows/store6-benchmarks.yml), path-filtered pull requests and `workflow_dispatch` | Report-only. Not a ready gate. |
| Codecov upload | `CI` workflow | Runs only on `MobileNativeFoundation/Store` with secrets. |
| Maven Central publish | `CI` workflow `publish` job | `main` pushes on `MobileNativeFoundation/Store` with secrets. |
| Create Swift Package | `workflow_dispatch` | Not a pull-request check. |

## Source ownership and sync

Edit the authoritative file for the change. Do not treat a generated or synchronized copy as the lasting source.

| What you are changing | Authoritative edit | How it reaches readers |
| --- | --- | --- |
| Paths listed in [`.github/docs-sync-sources.txt`](.github/docs-sync-sources.txt): `CONTRIBUTING.md`, `ROADMAP.md`, `STABILITY.md`, `docs/store6/important-defaults.md`, `docs/store6/invalidate-vs-clear.md`, `docs/store6/key-design.md`, `docs/store6/quickstart.md`, `llms.txt`, `store6-compose/README.md`, `store6-room/README.md`, `store6-sqldelight/README.md` | That file in this repository | After merge, the documentation site's scheduled drift check opens a re-pin pull request for `evidence/T4-store6-source-lock.json` and `sync-store6-docs`. `CONTRIBUTING.md` publishes to `content/docs/store6/contributing.mdx`. Do not hand-edit that MDX as the fix. A pull request that touches these paths needs the `docs-sync-ack` label. |
| `store6-graphql/README.md` and `store6-realtime/README.md` | Those files in this repository | The docs source lock also pins them. They are **not** in `.github/docs-sync-sources.txt`, so `docs-sync-guard` will not require `docs-sync-ack` for those two paths. |
| Site-owned docs (Overview, Concepts, Guides, Mutations, Paging, Migration) | The matching MDX in the documentation-site repository | Published at [store.mobilenativefoundation.org](https://store.mobilenativefoundation.org). Not edited in this repository. |
| Generated API reference (`store6-core`, `store6-mutations`) | Store6 KDoc / Dokka inputs | Regenerated as a complete tree in the documentation-site repository. Do not hand-edit generated HTML. |
| `SECURITY.md` | This repository | GitHub security policy. Not listed in `.github/docs-sync-sources.txt`. |
| Issue and pull-request templates | `.github/ISSUE_TEMPLATE/*` and [`pull_request_template.md`](pull_request_template.md) | GitHub issue/PR UI. Not docs-synced. |

`CODE_OF_CONDUCT.md` is a repository community file and is not docs-synced.

## License

[LICENSE](LICENSE) is Apache License 2.0. Contributions are accepted under Apache-2.0. There is no Contributor License Agreement and no Developer Certificate of Origin. Pull requests do not require `Signed-off-by`. The `-s` / `-sam` sign-off in [RELEASING.md](RELEASING.md) applies to the documented release-commit steps, not to ordinary contributions.

## Conduct

This repository includes `CODE_OF_CONDUCT.md`. A dedicated conduct-reporting contact is not specified, so this guide does not publish a conduct-reporting path.

## Pull requests

1. Fork [MobileNativeFoundation/Store](https://github.com/MobileNativeFoundation/Store) and create a branch.
2. Make the change in the authoritative file for its class above.
3. Add or update tests for the behavior you changed.
4. Run the hosted-CI commands that cover those modules and operating systems. Do not treat one command as the full matrix.
5. Open a pull request against `MobileNativeFoundation/Store` using [`pull_request_template.md`](pull_request_template.md). Link any issue number.
6. If you edited a path in `.github/docs-sync-sources.txt`, add the `docs-sync-ack` label.

Maintainers review the pull request and merge it. Update the branch if they request changes.
