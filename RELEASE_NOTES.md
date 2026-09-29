# MCP Connector for Apache Hop 2.2.3

Maintenance release dated 2026-09-29. Java 21, Apache Hop 2.19.0 baseline, Hop 2.20.0-SNAPSHOT compatibility profile, MCP Java SDK 2.0.1, protocol 2025-11-25 and STDIO production transport remain unchanged. No tools or permissions were added.

## Changes

- Reduced initial native metadata extraction and repeated filesystem ordering work, preserving typed reference sources, extraction limits, immutable snapshots, single-flight refresh, generation invalidation and external-change detection.
- Enforced the index read budget for malformed definitions and failed reads; incomplete filesystem scans retain partial results and accurate pagination signals.
- Applied Hop Web deadlines to both headers and bounded body reception, with cancellation on timeout/interruption.
- Preserved the native unavailable-error-count sentinel (`-1`) in synchronous and asynchronous timeout output schemas.
- Added full-distribution native-reference equivalence and MCP operational acceptance gates, mandatory Linux symlink coverage, and strict raw conformance-result verification. Fixed idle SSE headers in the test-only adapter; production remains STDIO.

## Measured performance and verification

The retained full-Hop 2.19.0 / Java 21 comparison uses an identical 5,000-definition fixture, five alternating fresh processes per version and five hot queries per process. Compared with 2.2.2, median first impact queries fell from 4,236.7 to 3,754.0 ms (11.4%), and hot queries from 125.5 to 19.5 ms (84.5%). Results match exactly: 64 nodes, 63 edges, no truncation. No startup improvement is claimed. These fixture results are not a guarantee for every project; see `docs/full-hop-benchmark.md` and `docs/production-verification.md` for scope and variation.

Local verification covered 124 Java tests, both Maven profiles, Linux symlink confinement, 16 native-reference cases across seven required plugins and 20 operational MCP checks on complete Hop installations. The application-profile conformance baseline explicitly retains 25 everything-server fixture failures; it does not claim complete everything-server conformance.

The tag-controlled release gate repeats hosted CI, verifies the clean Hop installation and Marketplace package, and publishes the ZIP, CycloneDX JSON/XML SBOMs, SHA-256 checksums, archive provenance and SBOM attestations. The Maven version, embedded resource, Marketplace catalog, ZIP name and release tag align at `2.2.3` / `v2.2.3`. Existing 2.2.2 assets are unchanged.
