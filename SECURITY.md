# Security policy

## Supported version

The current supported development line is 0.3.x on Apache Hop 2.19.x / Java 21.

## Design assumptions

Apache Hop pipeline/workflow definitions can contain executable behavior. Treat untrusted `.hpl`, `.hwf`, scripts, SQL, metadata and plugin configurations as untrusted code/data.

0.3.x exposes read-only project-inspection tools. It does not expose execution or mutation MCP tools.

Optional Hop Web access remains read-only in 0.3.x: only GET and HEAD are accepted. Requests are confined to an administrator-configured base URL, redirects are disabled, caller-provided authentication headers are rejected, and sensitive response data is redacted. Do not expose the STDIO process directly to untrusted remote users.

The native deep checker is disabled by default because some Hop transforms/actions may inspect fields or contact configured external systems while checking.

## Reporting

For a suspected vulnerability, avoid posting credentials, production configuration or exploit data in a public issue. Contact the repository owner privately through GitHub first and provide the minimum reproduction needed.
