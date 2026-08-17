# Store 6 roadmap

## Scope

This roadmap records what is observable in the bounded Store 6 development snapshot. It is not a
release schedule. Planned work and source-present modules do not establish publication,
compatibility, or release status.

<!-- store6-release-truth:begin -->
### Bounded release truth

`MobileNativeFoundation/Store` is the canonical authority. This record observes development snapshot `5a8c956bc1dbd6ad838ea9da3b34c7d76c703a71`, which has not landed there.

No Store 6 tag or Maven Central artifact is established. A release requires the canonical exact tag, the registry artifact and version, and release-note provenance.

The authoritative inventory is [docs/store6/artifact-manifest.json](./docs/store6/artifact-manifest.json). Publication evidence and API tier are separate from documentation-page status and misuse risk.

Source-present (13): store6-compose, store6-core, store6-devtools, store6-devtools-inspector, store6-graphql, store6-mutations, store6-mutations-sqldelight, store6-mutations-testing, store6-paging-androidx, store6-realtime, store6-room, store6-sqldelight, store6-testing.
Planned only (2): store6-bom, store6-store5-interop.

Generated references are not claimed to match this snapshot. Forecast windows are neutral estimates, not release dates or compatibility guarantees.
<!-- store6-release-truth:end -->

## Reading the inventory

Use [docs/store6/artifact-manifest.json](./docs/store6/artifact-manifest.json) for the complete
artifact record. Its `evidence` field distinguishes planned work, source presence, the exact
snapshot, and publication state. Its `tier` field is independent: stable-track, experimental,
no-api-surface, and unresolved do not make release or compatibility claims.

`store6-bom` and `store6-store5-interop` remain planned only. The Store 5 interop target is
intentionally unresolved. It must stay explicit until its source, publication, and API posture are
actually established.

## Forecast boundary

Future windows may be recorded as estimates when there is enough evidence to do so. They cannot
be read as a ship date or an immutable compatibility guarantee. A real release is established only
at the canonical exact tag with its registry artifact and version plus release-note provenance.
