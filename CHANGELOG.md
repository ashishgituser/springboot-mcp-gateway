# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Multi-module Maven project scaffolding: `mcp-gateway-core`, `mcp-gateway-autoconfigure`, `mcp-gateway-spring-boot-starter`, `mcp-gateway-sample`.
- CI workflow building and testing against Java 17 and 21.
- Spotless formatting check wired into the build.
- Multi-server routing and aggregation: the gateway connects to every configured upstream MCP server, namespaces their tools as `<serverId>__<toolName>` to avoid collisions, and exposes the merged tool list over a single streamable-HTTP MCP endpoint (`mcp.gateway.mcp-endpoint`, default `/mcp`).
- `mcp.gateway.*` configuration properties for declaring upstream servers and their per-server request timeout.
- Auth & policy enforcement: an allow/deny `PolicyEngine` evaluates each tool call against ordered `mcp.gateway.policy.rules` (matching on principal name, role, and glob patterns over namespaced tool names) before it reaches an upstream server. Disabled by default; once enabled, calls matching no rule fall back to `mcp.gateway.policy.default-effect` (`DENY` unless overridden).
- Caller identity bridges automatically from Spring Security's `SecurityContext` when it's on the classpath (as it is once [`mcp-server-security`](https://github.com/spring-ai-community/mcp-security) is added for OAuth2/API-key auth), with a servlet-principal fallback otherwise. Auth itself stays delegated to `mcp-server-security` — the gateway only adds policy on top of whatever it authenticates.
