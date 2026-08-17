#!/usr/bin/env python3
"""Validate the bounded Store 6 release-truth inventory."""

import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "docs/store6/artifact-manifest.json"
DOC_PATHS = (ROOT / "STABILITY.md", ROOT / "ROADMAP.md")
SNAPSHOT_SHA = "5a8c956bc1dbd6ad838ea9da3b34c7d76c703a71"
SOURCE_ARTIFACTS = {
    "store6-compose": "experimental",
    "store6-core": "stable-track",
    "store6-devtools": "experimental",
    "store6-devtools-inspector": "experimental",
    "store6-graphql": "experimental",
    "store6-mutations": "experimental",
    "store6-mutations-sqldelight": "experimental",
    "store6-mutations-testing": "experimental",
    "store6-paging-androidx": "experimental",
    "store6-realtime": "experimental",
    "store6-room": "experimental",
    "store6-sqldelight": "experimental",
    "store6-testing": "experimental",
}
PLANNED_ARTIFACTS = {
    "store6-bom": "no-api-surface",
    "store6-store5-interop": "unresolved",
}
EVIDENCE_STATES = {
    "planned",
    "source-present",
    "snapshot",
    "unpublished",
    "reference-available",
    "observed-at-tag",
}
TIERS = {"stable-track", "experimental", "no-api-surface", "unresolved"}


def fail(message: str) -> None:
    raise ValueError(message)


def release_truth_block(artifacts: list[dict[str, object]]) -> str:
    source_names = ", ".join(
        artifact["artifact"]
        for artifact in artifacts
        if artifact["evidence"][0] == "source-present"
    )
    planned_names = ", ".join(
        artifact["artifact"]
        for artifact in artifacts
        if artifact["evidence"][0] == "planned"
    )
    return "\n".join(
        (
            "<!-- store6-release-truth:begin -->",
            "### Bounded release truth",
            "",
            "`MobileNativeFoundation/Store` is the canonical authority. This record observes development snapshot "
            f"`{SNAPSHOT_SHA}`, which has not landed there.",
            "",
            "No Store 6 tag or Maven Central artifact is established. A release requires the canonical exact tag, "
            "the registry artifact and version, and release-note provenance.",
            "",
            "The authoritative inventory is [docs/store6/artifact-manifest.json](./docs/store6/artifact-manifest.json). "
            "Publication evidence and API tier are separate from documentation-page status and misuse risk.",
            "",
            f"Source-present (13): {source_names}.",
            f"Planned only (2): {planned_names}.",
            "",
            "Generated references are not claimed to match this snapshot. Forecast windows are neutral estimates, "
            "not release dates or compatibility guarantees.",
            "<!-- store6-release-truth:end -->",
        )
    )


def marked_block(text: str) -> str:
    start = "<!-- store6-release-truth:begin -->"
    end = "<!-- store6-release-truth:end -->"
    try:
        return text[text.index(start) : text.index(end) + len(end)]
    except ValueError as error:
        raise ValueError("missing bounded release-truth block") from error


def main() -> None:
    manifest = json.loads(MANIFEST_PATH.read_text())
    if manifest.get("schema_version") != 1:
        fail("schema_version must be 1")

    authority = manifest.get("authority", {})
    expected_authority = {
        "canonical_repository": "MobileNativeFoundation/Store",
        "snapshot_commit": SNAPSHOT_SHA,
        "snapshot_kind": "development-snapshot",
        "canonical_landing": "not-landed",
        "store6_tag": None,
        "maven_central_artifact": None,
    }
    if authority != expected_authority:
        fail("authority must describe the approved bounded snapshot")

    if set(manifest.get("artifact_evidence_states", ())) != EVIDENCE_STATES:
        fail("artifact_evidence_states must declare the approved state vocabulary")
    if set(manifest.get("tiers", ())) != TIERS:
        fail("tiers must declare the approved tier vocabulary")

    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list):
        fail("artifacts must be a list")
    names = [artifact.get("artifact") for artifact in artifacts]
    expected_names = sorted(SOURCE_ARTIFACTS | PLANNED_ARTIFACTS)
    if names != expected_names:
        fail("artifacts must be the exact sorted Store 6 inventory")

    for artifact in artifacts:
        name = artifact["artifact"]
        tier = artifact.get("tier")
        evidence = artifact.get("evidence")
        if tier not in TIERS:
            fail(f"{name}: invalid tier")
        if not isinstance(evidence, list) or not set(evidence) <= EVIDENCE_STATES:
            fail(f"{name}: invalid evidence")
        if name in SOURCE_ARTIFACTS:
            if tier != SOURCE_ARTIFACTS[name]:
                fail(f"{name}: unexpected source-present tier")
            if evidence != ["source-present", "snapshot", "unpublished"]:
                fail(f"{name}: source-present evidence must be bounded and unpublished")
            source_directory = artifact.get("source_directory")
            if source_directory != name or not (ROOT / source_directory / "src").is_dir():
                fail(f"{name}: source directory must exist")
        else:
            if tier != PLANNED_ARTIFACTS[name]:
                fail(f"{name}: unexpected planned tier")
            if evidence != ["planned", "snapshot", "unpublished"]:
                fail(f"{name}: planned evidence must be bounded and unpublished")
            if "source_directory" in artifact:
                fail(f"{name}: planned artifacts cannot claim a source directory")

    expected_block = release_truth_block(artifacts)
    for doc_path in DOC_PATHS:
        if marked_block(doc_path.read_text()) != expected_block:
            fail(f"{doc_path.name}: release-truth block differs from the manifest")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Store 6 artifact manifest validation failed: {error}", file=sys.stderr)
        sys.exit(1)
