# Apache Hop MCP 0.4.0 — Local execution and transactional semantic mutation

First release that can safely run and modify Apache Hop definitions through MCP when explicitly authorized.

- Adds synchronous and asynchronous local pipeline/workflow execution, status, cancellation, bounded concurrency, timeouts, and redacted logs.
- Restricts MCP execution to local Apache Hop engine run configurations; remote engines are rejected.
- Adds previewable native semantic mutations for names, descriptions, component rename/move/removal, and hop add/remove/enabled state.
- Exposes `hop_capabilities` as a machine-readable semantic contract and adds explicit live synchronization with Hop Desktop and Hop Web.
- Opens or reloads changed definitions through native Hop UI APIs, closes rolled-back created definitions, and never overwrites tabs with unsaved changes.
- Uses a bounded project-local event bridge with expiring session heartbeats and one isolated server-push adapter per Hop Web browser session.
- Requires SHA-256 preconditions for existing definitions and performs backup, atomic replacement, native reload validation, automatic failure recovery, and explicit session rollback.
- Keeps execution and applied mutation disabled by default behind separate `--allow-execution` and `--allow-mutation` flags.
- Keeps Hop Web access read-only (`GET` / `HEAD`) and opt-in.
- Preserves project-root confinement, hardened XML parsing, bounded reads/results, and secret redaction.
- Runs directly in the Apache Hop JVM on Java 21 with MCP Java SDK 2.0.1 over STDIO; no Python or Java subprocess bridge is required.
- Built against Apache Hop 2.19.0 and compatibility-tested against the current Apache Hop 2.20.0-SNAPSHOT line.
- GitHub Actions runs `mvn -B clean verify` and validates the Marketplace ZIP before creating `v0.4.0`.

This is a community project, not an official Apache Software Foundation release.
