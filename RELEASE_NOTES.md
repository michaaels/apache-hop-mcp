# Apache Hop MCP 0.3.0 — Marketplace Edition

First native-Java Marketplace edition.

- One Marketplace plugin installs both the Hop GUI integration and `hop mcp` headless command.
- Runs directly in the Apache Hop JVM on Java 21; Python and the old Java subprocess bridge are not required.
- Uses MCP Java SDK 2.0.1 over STDIO.
- Ships 12 read-only tools for project inspection, search, lineage, validation, plugin inventory and dependencies.
- Constrains filesystem access to a configured project root and uses a hardened XML parser.
- Redacts secret-looking fields from component inspection.
- Apache Hop native deep checking is opt-in because it can access configured external systems.
- Built against Apache Hop 2.19.0 and intended for the 2.19.x line.
- GitHub Actions is the release gate: `mvn clean verify` and Marketplace ZIP validation must succeed before `v0.3.0` is created.

This is a community project, not an official Apache Software Foundation release.
