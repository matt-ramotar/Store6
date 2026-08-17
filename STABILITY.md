# Store 6 stability policy

## Scope

This policy describes release truth for Store 6 artifacts. It does not promote a source checkout to
a release. Artifact publication evidence, API tier, and documentation-page status are different
facts and must be read separately.

<!-- store6-release-truth:begin -->
### Bounded release truth

`MobileNativeFoundation/Store` is the canonical authority. This record observes development snapshot `5a8c956bc1dbd6ad838ea9da3b34c7d76c703a71`, which has not landed there.

No Store 6 tag or Maven Central artifact is established. A release requires the canonical exact tag, the registry artifact and version, and release-note provenance.

The authoritative inventory is [docs/store6/artifact-manifest.json](./docs/store6/artifact-manifest.json). Publication evidence and API tier are separate from documentation-page status and misuse risk.

Source-present (13): store6-compose, store6-core, store6-devtools, store6-devtools-inspector, store6-graphql, store6-mutations, store6-mutations-sqldelight, store6-mutations-testing, store6-paging-androidx, store6-realtime, store6-room, store6-sqldelight, store6-testing.
Planned only (2): store6-bom, store6-store5-interop.

Generated references are not claimed to match this snapshot. Forecast windows are neutral estimates, not release dates or compatibility guarantees.
<!-- store6-release-truth:end -->

## Evidence states

The manifest uses these evidence states:

- `planned`: intended artifact with no source-present claim.
- `source-present`: a module directory and source exist in this checkout.
- `snapshot`: evidence is bounded to the manifest's exact development snapshot.
- `unpublished`: no Maven Central artifact or release version is established.
- `reference-available`: a reference is available only when the manifest records that evidence.
- `observed-at-tag`: evidence observed at a canonical release tag.

`released` is not an evidence state for this snapshot. It is justified only by the three release
requirements in the bounded release-truth record.

## API tiers and use risk

- `stable-track`: intended for stability work, but not a release or compatibility promise.
- `experimental`: API shape may change. This tier is separate from whether the artifact is
  published.
- `no-api-surface`: an alignment or metadata artifact with no API surface.
- `unresolved`: the target or tier has not been established.

Tier describes the intended API posture. Publication evidence describes whether something can be
consumed as a released artifact. Misuse risk is a separate concern and is not inferred from either
field.

## Forecasts and references

Any future window is a neutral planning estimate. It is neither an immutable date nor a
compatibility guarantee before a canonical tag, registry artifact, and release notes exist.

Generated API or Swift references may describe another revision. This policy does not claim that
any generated reference matches the snapshot recorded above.
