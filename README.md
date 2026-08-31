# Apache Hop MCP 0.3.0

Community Model Context Protocol (MCP) server plugin for **Apache Hop 2.19.x**.

> This is a community project and is not an official Apache Software Foundation project.

## What changed in 0.3

Version 0.3 is the Marketplace/Java edition. The MCP server runs directly in the Apache Hop JVM. Python and the v0.2 Java subprocess bridge are **not required** for the Marketplace plugin.

```text
Codex / Claude / Qwen
        |
      MCP STDIO
        |
     hop mcp
        |
  Apache Hop JVM
  + PluginRegistry
  + PipelineMeta / WorkflowMeta
```

Installing the plugin once makes both integrations available after restarting Hop:

- `hop mcp` / `hop.bat mcp` — headless MCP server for Codex and other MCP clients.
- **Tools → Apache Hop MCP…** — GUI installation/status information.

## Security model

0.3.0 is intentionally **read-only**. It does not expose pipeline/workflow execution or mutation tools.

The project root is a hard boundary. Paths are normalized and resolved with real paths so path traversal and symlinks cannot escape it. XML parsing disables DTDs and external entities. Large file reads and project scans are bounded. Secret-looking XML fields are redacted in `hop_component`.

`hop_deep_check` is a special case: Apache Hop's native checker can resolve fields or contact configured databases/services. It is disabled unless the server is started with `--allow-deep-check`.

## Requirements

- Apache Hop **2.19.x**
- Java **21**
- MCP client with STDIO support

## Build

```bash
mvn -B clean verify
```

The Marketplace artifact is:

```text
target/apache-hop-mcp-0.3.0.zip
```

The ZIP expands into:

```text
plugins/misc/apache-hop-mcp/
  apache-hop-mcp-0.3.0.jar
  version.xml
  lib/...
```

Apache Hop jars are `provided` and are not bundled in the plugin ZIP.

## Marketplace installation

After the `v0.3.0` GitHub Release exists, import this repository definition from the repository:

```text
marketplace/hop-marketplace-repo.yaml
```

Then use Hop Marketplace to search/install **Apache Hop MCP**, or install the exact coordinate after importing the repository:

```bash
./hop marketplace install io.github.michaaels:apache-hop-mcp:0.3.0 --repo apache-hop-mcp
```

Restart Hop after installation.

The repository definition uses GitHub Releases through Hop 2.19's `urlTemplate` + `catalogUrl` support; no Nexus server is required.

## Headless command

Linux/macOS:

```bash
./hop mcp --root /data/hop/project
```

Windows:

```powershell
hop.bat mcp --root C:\Hop\project
```

If the Projects plugin is configured, Hop's normal run-category project/environment options are also loaded by the command.

Enable the native deep checker only when external metadata/database access is acceptable:

```bash
./hop mcp --root /data/hop/project --allow-deep-check
```

## Codex configuration

Example `~/.codex/config.toml`:

```toml
[mcp_servers.apache-hop]
command = "/opt/hop/hop"
args = ["mcp", "--root", "/data/hop/project"]
```

Windows:

```toml
[mcp_servers.apache-hop]
command = "C:\\hop\\hop.bat"
args = ["mcp", "--root", "C:\\Hop\\project"]
```

## MCP tools

| Tool | Purpose |
|---|---|
| `hop_config` | server/root/security configuration |
| `hop_plugins` | Apache Hop `PluginRegistry` inventory |
| `hop_list_definitions` | list `.hpl` / `.hwf` definitions |
| `hop_inspect` | components, hops, SQL tables, references |
| `hop_component` | inspect one transform/action with secret redaction |
| `hop_component_lineage` | upstream/downstream graph traversal |
| `hop_validate` | safe structural validation |
| `hop_deep_check` | native Apache Hop checker; explicit opt-in |
| `hop_read_text` | bounded project file read |
| `hop_search` | bounded text search |
| `hop_find_table` | SQL table-reference discovery |
| `hop_dependencies` | referenced `.hpl` / `.hwf` dependencies |

## AI-assisted development

Codex and other repository agents should read [`AGENTS.md`](AGENTS.md). Detailed Cloud/CLI build instructions are in [`docs/AI_DEVELOPMENT.md`](docs/AI_DEVELOPMENT.md).

## Roadmap

0.4 is intended to add **native semantic mutation** with explicit write opt-in, content-hash preconditions, preview/diff, backup, atomic replace, reload validation, and rollback.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
