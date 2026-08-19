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
- Observability: every tool call is recorded as a Micrometer `mcp.gateway.tool.call` observation (tagged by tool name and outcome), so metrics — and traces, once a tracing bridge is added — are exported the moment `spring-boot-starter-actuator` is on the classpath. An `upstreams` health indicator pings each configured upstream and reports per-server `UP`/`DOWN` under `/actuator/health`. A structured JSON audit log line is written per call via SLF4J (`mcp.gateway.audit.enabled`, default `true`), independent of Actuator.
- Rate limiting: an opt-in `RateLimiter` SPI (`mcp.gateway.rate-limit.*`), with a [Bucket4j](https://bucket4j.com)-backed in-memory default. Calls over quota are rejected before reaching an upstream, recorded as a `rate_limited` outcome in metrics and the audit log. Quota can be scoped per caller, per tool, or per (caller, tool) pair.
- Architecture tests (ArchUnit) locking in the module boundaries: `mcp-gateway-core` can't depend on Spring or Servlet classes, its policy/rate-limit/observability packages can't depend back on the router, and `mcp-gateway-autoconfigure` beans are wired only through constructors/`@Bean` methods, never field injection.
- An end-to-end integration test (`mcp-gateway-sample`) that boots two real MCP servers in-process as upstreams and drives the gateway over its real streamable-HTTP endpoint with a real MCP client, asserting tool aggregation/namespacing, policy denial, and rate-limit enforcement without any mocking.
