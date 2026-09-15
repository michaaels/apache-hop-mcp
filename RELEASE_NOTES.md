# Apache Hop MCP 0.6.0 — Tabular semantic component authoring

This release extends native semantic authoring to repeated/tabular component configuration, enabling useful field lists and similar metadata structures without editing XML.

- Adds synchronous and asynchronous local pipeline/workflow execution, status, cancellation, bounded concurrency, timeouts, and redacted logs.
- Restricts MCP execution to local Apache Hop engine run configurations; remote engines are rejected.
- Discovers installed transforms/actions and derives safe scalar and tabular configuration schemas from Hop's metadata-injection model.
- Adds configured native transforms/actions with scalar `properties` and repeated `property_groups`, composable with hop operations in one transaction.
- Supports one-level native collection injection for transforms such as Injector while excluding deeper nested structures.
- Bounds groups, rows and total cells, and rejects unknown, oversized and secret-looking component properties.
- Exposes `hop_capabilities` as a machine-readable semantic contract and adds explicit live synchronization with Hop Desktop and Hop Web.
- Opens or reloads changed definitions through native Hop UI APIs, closes rolled-back created definitions, and never overwrites tabs with unsaved changes.
- Uses a bounded project-local event bridge with expiring session heartbeats and one isolated server-push adapter per Hop Web browser session.
- Requires SHA-256 preconditions for existing definitions and performs backup, atomic replacement, native reload validation, automatic failure recovery, and explicit session rollback.
- Keeps execution and applied mutation disabled by default behind separate `--allow-execution` and `--allow-mutation` flags.
- Keeps Hop Web access read-only (`GET` / `HEAD`) and opt-in.
- Preserves project-root confinement, hardened XML parsing, bounded reads/results, and secret redaction.
- Runs directly in the Apache Hop JVM on Java 21 with MCP Java SDK 2.0.1 over STDIO; no Python or Java subprocess bridge is required.
- Built against Apache Hop 2.19.0 and compatibility-tested against the current Apache Hop 2.20.0-SNAPSHOT line.
- GitHub Actions runs `mvn -B clean verify` and validates the Marketplace ZIP before creating `v0.6.0`.

This is a community project, not an official Apache Software Foundation release.
