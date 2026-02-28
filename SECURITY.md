# Security

## Important

This app runs an **unauthenticated HTTP server** on your local network. By design, anyone on the same network can send requests to the API. Do not expose this server to the public internet.

## Reporting Vulnerabilities

If you discover a security issue, please open a GitHub issue or contact the maintainer directly. Since this is a local-network tool, most security concerns relate to network exposure rather than data handling.

## Scope

- The server binds to all network interfaces on the configured port
- No authentication or API keys are required
- All inference is local — no data is sent to external servers
- CORS is permissive (`*`) for local development convenience
