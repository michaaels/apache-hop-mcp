# MCP Connector for Apache Hop 2.2.5

Maintenance release dated 2026-10-01. Java 21, Apache Hop 2.19.0 baseline, Hop 2.20.0-SNAPSHOT compatibility profile, MCP Java SDK 2.0.1, protocol 2025-11-25 and STDIO production transport remain unchanged. No tools or permissions were added.

## Changes

- Canonicalized temporary test roots for Windows runners that supply 8.3 path aliases, preserving all security assertions.
- Confined project reads and semantic writes to resolved public project paths. Windows junctions are excluded from traversal and protected backup directories; aliases cannot expose internal backups or external files.
- Reject new deep-check tasks with the retryable `DEEP_CHECK_WORKER_UNHEALTHY` error while a cancelled JDBC callable is still running. The bounded worker recovers after the callable actually exits.
- Verify native reference acceptance against the installed connector JAR, including its CodeSource and package hash, using a separate test helper artifact.
- Strengthened raw conformance checks while accepting repeatable informational transport events. Informational entries cannot satisfy required checks, hide failures or override scored results.
- Bounded STDIO test reader cleanup and preserved Hop Web percent-encoded query parameters without double escaping.
- Select a published stable ancestor for benchmarks, added a manual Windows full-Hop workflow with per-role evidence, and preserved existing output directories when a benchmark invocation is rejected.

## Verification and scope

Local validation of the maintenance changes passed Maven verification with Java 21: 133 tests, zero failures and three Windows symlink skips. All three mandatory Windows junction tests executed successfully. The 16 verification-guard tests passed, and the corrected conformance guard accepted all 33 retained real runner scenarios with the explicit application-profile baseline. The baseline retains 25 everything-server fixture failures; this is not a claim of complete everything-server conformance.

The retained pre-release benchmark measured the maintenance build while it still carried version 2.2.3 (ZIP SHA-256 `c7a387ab29b278f5dbd6dd69c07029d7d0a15e89d0ddbcf5069525baa29a618d`) against published 2.2.2. With five alternating fresh processes per version and five hot queries per process, median first impact queries increased from 3,776.3 to 4,214.4 ms (+11.6%), while hot queries fell from 104.7 to 17.3 ms (-83.4%). All results matched: 64 nodes, 63 edges and no truncation. These figures describe that retained fixture run, not a new timing measurement of the final 2.2.5 ZIP or a guarantee for every project.

The tag-controlled release workflow requires hosted Linux and Windows verification, Hop 2.20 compatibility, MCP conformance and clean Hop 2.19 installed-plugin acceptance before publishing. Release assets include the Marketplace ZIP, CycloneDX JSON/XML SBOMs and SHA-256 checksums, with archive provenance and SBOM attestations. Release coordinates align at `2.2.5` / `v2.2.5`.
