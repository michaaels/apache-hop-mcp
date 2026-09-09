# Changelog

## 0.3.1 - 2026-09-08

- Added bounded, paginated project cataloging with file metadata and SHA-256 fingerprints.
- Added consolidated definition context and project-relative dependency resolution.
- Added filtered, paginated Apache Hop plugin discovery.
- Added opt-in, bounded Hop Web GET/HEAD access with base-path confinement and secret redaction.
- Made MCP JSON mapper and schema validator selection explicit for Hop's isolated plugin classloader.
- Routed Hop console logging to stderr while STDIO is active so stdout remains JSON-RPC-only.
- Extended error, XML, response-body and response-header secret redaction.

## 0.3.0 - 2026-08-31

- Rebuilt Marketplace runtime in native Java 21.
- Added Apache Hop `@HopCommand` integration as `hop mcp`.
- Added Hop GUI Tools menu integration.
- Replaced Python/Java bridge runtime with direct MCP Java SDK 2.0.1 integration.
- Added 12 read-only MCP tools for inspection, search, lineage, validation and dependencies.
- Added project-root confinement, bounded scanning, secure XML parser and secret redaction.
- Added opt-in Apache Hop native deep checker.
- Added Marketplace GitHub Releases catalog/repository metadata.
- Added Codex/Cloud `AGENTS.md` and AI development guide.
