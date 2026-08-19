# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] - 2026-08-19

Positions the gateway as a governance layer rather than a proxy, and makes it deployable on its own.

### Added
- **Authorization-aware tool discovery.** `tools/list` now runs through the same `PolicyEngine` as `tools/call`, so a caller is only shown tools it could actually invoke — a denied tool's name, description and argument schema are no longer disclosed to everyone who can reach the endpoint. On by default; set `mcp.gateway.policy.filter-tool-list=false` for the previous behaviour. Pluggable via a `ToolVisibility` bean.
- **Upstream resilience.** Sessions are opened on first use instead of at startup, so an unreachable MCP server no longer fails the gateway's context. Each upstream is guarded by a `CircuitBreaker` (three consecutive transport failures open it for 30s, then one probe decides); protocol errors don't count towards it, since the server answered. Catalog refresh skips upstreams it can't reach rather than failing.
- **Periodic catalog refresh** (`mcp.gateway.refresh-interval`, default `60s`, `0` to disable), so an upstream deployed after the gateway joins by itself and tools added or removed upstream are picked up without a restart. Connected clients get `notifications/tools/list_changed` when the published set changes.
- **Audit argument capture with redaction.** `mcp.gateway.audit.include-arguments` (default `false`) records what the caller asked for; keys matching `mcp.gateway.audit.redact` are masked, not dropped. Composed as `AuditLogger` decorators.
- **Redis-backed distributed rate limiting** (`mcp.gateway.rate-limit.store=REDIS`), so quota is shared by every replica instead of being multiplied by their count. Refill and consume run as one Lua script — without atomicity two replicas can both see the last token and both spend it — and elapsed time comes from Redis's own clock, so clock skew between replicas doesn't change how much a bucket refills. Needs `spring-boot-starter-data-redis`, which is an optional dependency.
- **`mcp-gateway-server`** — a standalone, container-ready distribution for teams that want the gateway as its own deployable rather than embedded in an existing application. Graceful shutdown, liveness/readiness probes, non-root image.
- **`mcp-gateway-demo-upstream`** and a `docker compose up --build` quickstart that stands up a gateway in front of two MCP servers with deny-by-default policy, needing nothing installed but Docker.
- Container images for both deployables published to GHCR on `main` and on tags.
- `docs/architecture.md` (module boundaries, request lifecycle, extension points) and `docs/security.md` (trust boundary, threat coverage, and an explicit list of what the gateway does not do).
- `mcp.gateway.request-timeout` for the gateway's own MCP session timeout.

### Changed
- The gateway now installs its own MCP request handlers on the transport provider instead of building an SDK `McpServer` over a fixed tool list. The SDK answers `tools/list` identically for every caller with no per-request hook, which per-principal filtering requires. This also leaves room to proxy resources and prompts later.
- `AuditEvent` gained an `arguments` component. The previous six-argument constructor still exists and passes `null`.
- `UpstreamServer` is a class rather than a record, and exposes `listTools()`/`callTool()`/`ping()` instead of a raw `client()`, so failures can be observed by the circuit breaker. The `(id, client)` constructor is unchanged.
- `RateLimitScope` now derives the bucket key (`scope.key(principal, tool)`), so the in-memory and Redis limiters partition traffic identically.
- The glob matcher moved from `policy.ToolPattern` to `core.util.Glob`; `ToolPattern` delegates to it and its API is unchanged.
- README rewritten around the problem and the enterprise positioning, with an honest compatibility matrix — including what is *not* supported (SSE and stdio transports, resources and prompts, distributed rate limiting, Spring Boot 3.x).

### Removed
- The `McpSyncServer` bean. Applications that injected it should use `GatewayRouter` or `ToolRegistry` instead.


## [0.1.1] - 2026-08-19

First release published to Maven Central.

### Fixed
- `mcp-gateway-sample` (the demo app, deliberately shipped without sources/javadoc jars) was getting swept into the Maven Central upload bundle despite `maven.deploy.skip`, since `central-publishing-maven-plugin` runs as a build extension that bypasses that flag — failing validation for the whole deployment. Excluded it via the plugin's own `skipPublishing` override instead.

### Added
- The `release` workflow now takes an `auto_publish` input: leave it off to land a deployment as pending-review in the Central Portal (the default, and what every prior release profile run did), or check it to publish immediately once Central validates the upload.

## [0.1.0] - 2026-08-19

First feature-complete milestone: all four MVP features implemented and tested end to end. Not yet on Maven Central — see [Getting it](README.md#getting-it) for the JitPack coordinates in the meantime.

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

[Unreleased]: https://github.com/ashishgituser/springboot-mcp-gateway/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/ashishgituser/springboot-mcp-gateway/releases/tag/v0.2.0
[0.1.1]: https://github.com/ashishgituser/springboot-mcp-gateway/releases/tag/v0.1.1
[0.1.0]: https://github.com/ashishgituser/springboot-mcp-gateway/releases/tag/v0.1.0
