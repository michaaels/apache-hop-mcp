# MCP Connector for Apache Hop 2.2.6

Maintenance release dated 2026-10-01. Java 21, Apache Hop 2.19.0 baseline, Hop 2.20.0-SNAPSHOT compatibility profile, MCP Java SDK 2.0.1, protocol 2025-11-25 and STDIO production transport remain unchanged. No tools or permissions were added.

## Changes

- Keep stdout reserved for MCP for the full lifetime of the CLI process. Previously, returning from the MCP command restored stdout while native pipeline workers could still emit their final logs. Those late messages could corrupt the JSON protocol after a timeout, cancellation or EOF. Logs now remain on stderr through process exit, including startup and shutdown failures.
- Add deterministic regression tests that emit native Hop logs and plugin console output after normal EOF and after a startup failure. Both tests reproduced the original stdout contamination before the fix.
- Restore the README's 2.2.3 compatibility entry and add detailed recent-version history. Explain that 2.2.4 was a tag-only attempt whose failed Windows verification blocked release assets; its corrected maintenance changes shipped in 2.2.5.

Execution results, cancellation behavior, tools, permission flags and resource bounds are unchanged. This release includes the maintenance improvements from 2.2.5.

## Verification and scope

The stdout fix passed 135 local Java tests with zero failures and three Windows symlink skips. Its hosted CI passed the stable build, Windows junction checks, MCP conformance, installed-plugin acceptance on Hop 2.19.0 and Hop 2.20 snapshot compatibility. The snapshot job needed one retry after a separate `Pipe broken` error in the existing STDIO integration test; the command and STDIO tests also passed locally with that profile.

The conformance baseline retains 25 everything-server fixture failures; this is not a claim of complete everything-server conformance. This release makes no new performance claim.

The tag-controlled release workflow repeats hosted verification before publishing. Assets include the Marketplace ZIP, CycloneDX JSON/XML SBOMs and SHA-256 checksums, with archive provenance and SBOM attestations. Release coordinates align at `2.2.6` / `v2.2.6`.
