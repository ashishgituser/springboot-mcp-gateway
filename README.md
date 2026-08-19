# Spring Boot MCP Gateway

[![CI](https://github.com/ashishgituser/springboot-mcp-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/ashishgituser/springboot-mcp-gateway/actions/workflows/ci.yml)
[![Release](https://jitpack.io/v/ashishgituser/springboot-mcp-gateway.svg)](https://jitpack.io/#ashishgituser/springboot-mcp-gateway)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-brightgreen.svg)](https://spring.io/projects/spring-boot)

A Spring Boot starter that puts a single gateway in front of multiple [MCP](https://modelcontextprotocol.io) (Model Context Protocol) servers: one endpoint for clients, with routing, auth/policy enforcement, observability and rate limiting handled centrally instead of in every downstream server.

> **Status:** early development. All four MVP features — routing/aggregation, auth/policy enforcement, observability and rate limiting — are implemented and covered by unit, architecture and end-to-end integration tests (see [Testing](#testing)). Not yet published to Maven Central — see [CHANGELOG.md](CHANGELOG.md) for progress.

## Architecture

<img src="docs/architecture.svg" alt="Architecture: clients call one MCP endpoint; the gateway authenticates, throttles, routes and fans out to upstream MCP servers" width="100%">

Clients connect to a single MCP endpoint. The gateway authenticates the caller (delegated to `mcp-security`), checks the call against policy, applies quota, resolves the namespaced tool name to the upstream that owns it, and forwards the call over an MCP client connection. Tool catalogs from every upstream are merged into one registry at startup, so clients see a single list of tools. Every call is metered, health-checked and audit-logged along the way — see [Observability](#observability) below.

## Why

Teams running several internal MCP servers end up duplicating auth, logging and throttling in each one. This gateway centralizes that: agents/clients talk to one MCP endpoint, and the gateway aggregates upstream tools, enforces who can call what, and gives you metrics and audit logs for every call.

## Modules

| Module | Purpose |
|---|---|
| `mcp-gateway-core` | Domain logic — routing, policy engine, rate-limit SPI, audit model. No Spring Boot dependency. |
| `mcp-gateway-autoconfigure` | `@AutoConfiguration` classes and `@ConfigurationProperties` binding `mcp.gateway.*`. |
| `mcp-gateway-spring-boot-starter` | The dependency you add to your app — pulls in autoconfigure plus required transitive deps. |
| `mcp-gateway-sample` | Runnable Spring Boot app demonstrating usage. |

## Requirements

- Java 17+
- Spring Boot 4.1.x

## Getting it

Not yet on Maven Central (see [Roadmap](#roadmap)) — until then, the tagged releases are built on demand by [JitPack](https://jitpack.io/#ashishgituser/springboot-mcp-gateway):

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.ashishgituser.springboot-mcp-gateway</groupId>
  <artifactId>mcp-gateway-spring-boot-starter</artifactId>
  <version>v0.1.0</version>
</dependency>
```

Swap `v0.1.0` for any tag, or a commit hash for an untagged build. See [Releases](https://github.com/ashishgituser/springboot-mcp-gateway/releases) for what's in each version.

## Building locally

```bash
mvn -B verify
```

## Configuration

```yaml
mcp:
  gateway:
    enabled: true       # default true; set false to disable the gateway entirely
    mcp-endpoint: /mcp  # where the gateway exposes its own MCP endpoint
    servers:
      - id: filesystem
        endpoint: http://localhost:8081/mcp
        request-timeout: 10s
      - id: database
        endpoint: http://localhost:8082/mcp
```

Each upstream server's tools are exposed under a namespaced name, `<serverId>__<toolName>` (e.g. `filesystem__readFile`), so tools from different servers never collide.

### Auth & policy

Authentication is delegated entirely to [mcp-security](https://github.com/spring-ai-community/mcp-security) — add `org.springaicommunity:mcp-server-security-spring-boot` and configure it per its own docs (OAuth2 resource server or API keys). The gateway adds authorization on top: an allow/deny policy engine that decides whether an authenticated (or anonymous) caller may invoke a given tool.

```yaml
mcp:
  gateway:
    policy:
      enabled: true      # default false — until enabled, every call is forwarded as before
      default-effect: DENY  # applied when no rule below matches; ALLOW is also valid
      rules:
        # Rules are evaluated top to bottom; the first match wins, so put narrow
        # exceptions above the broad rule they override.
        - effect: DENY
          tools: ["filesystem__delete*"]
        - effect: ALLOW
          roles: ["admin"]
          tools: ["*"]
        - effect: ALLOW
          principals: ["ci-bot"]
          tools: ["database__query"]
```

`principals`, `roles` and `tools` are all optional per rule — an omitted constraint matches anything, and `tools` entries are globs over the *namespaced* tool name (`*` and `?` wildcards). Roles only populate when Spring Security authenticates the caller (e.g. via `mcp-security`); without it, callers resolve to the servlet principal's name with no roles, so role-based rules never match.

### Observability

Every tool call is recorded three ways, none of which require any configuration to start working:

- **Metrics** — a `mcp.gateway.tool.call` timer, tagged with `tool.name` and `outcome` (`allowed`, `denied`, `rate_limited`, `not_found`, `error`), recorded through Micrometer's `ObservationRegistry`. Add `spring-boot-starter-actuator` and it's exported over `/actuator/metrics` automatically; add a `micrometer-tracing` bridge and the same observation produces spans too.
- **Health** — an `upstreams` health indicator pings every configured upstream's MCP session and reports `UP`/`DOWN` per server under `/actuator/health` (requires `spring-boot-starter-actuator`).
- **Audit log** — a structured JSON line per call (`principal`, `tool`, `outcome`, `durationMs`, `reason`) written via SLF4J on the `io.github.ashishgituser.mcpgateway.audit` logger, so it can be routed to its own file or log sink independently of application logs.

```yaml
mcp:
  gateway:
    audit:
      enabled: true  # default true — writes a log line per call, no behaviour change
```

None of this requires Actuator: without it, calls still flow through a no-op `ObservationRegistry` and the audit log keeps writing — metrics and the health endpoint just aren't exported anywhere until you add it.

### Rate limiting

Off by default. Once enabled, each call consumes one token from an in-memory [Bucket4j](https://bucket4j.com) bucket; the bucket refills at a fixed rate up to a capacity. Calls over quota are rejected before reaching an upstream, the same as a policy denial.

```yaml
mcp:
  gateway:
    rate-limit:
      enabled: true       # default false
      capacity: 100        # max tokens a bucket can hold
      refill-tokens: 100    # tokens added per refill-period
      refill-period: 1m
      scope: PRINCIPAL     # PRINCIPAL (default) | TOOL | PRINCIPAL_AND_TOOL
```

`scope` decides what the quota is shared across: `PRINCIPAL` gives each caller one quota for every tool they call, `TOOL` gives each tool one shared quota across every caller, and `PRINCIPAL_AND_TOOL` tracks a separate quota per (caller, tool) pair. Bring your own `RateLimiter` bean to swap in a distributed limiter (e.g. Redis-backed) instead of the in-memory default.

## Testing

`mvn -B verify` runs three layers of tests, all in CI on Java 17 and 21 (see the badge above):

- **Unit tests** in `mcp-gateway-core` and `mcp-gateway-autoconfigure` — the router, policy engine, rate limiter and Spring wiring, mock-driven.
- **Architecture tests** (ArchUnit) in both modules — enforce that `mcp-gateway-core` never depends on Spring or Servlet classes, that its policy/rate-limit/observability packages never depend back on the router that consumes them, and that every `@AutoConfiguration` class is wired through constructors, not field injection.
- **An end-to-end integration test** in `mcp-gateway-sample` — boots two real MCP servers in-process as upstreams and a real MCP client against the gateway's own HTTP endpoint, over the actual streamable-HTTP transport. It asserts tool aggregation/namespacing, that a policy-denied call never reaches the upstream, and that a rate-limited call is rejected once its quota is exhausted — using upstream invocation counters as the proof, not just response shape.

## Roadmap

- [x] Multi-module project scaffolding
- [x] Multi-server routing/aggregation
- [x] Auth & policy enforcement (built on [mcp-security](https://github.com/spring-ai-community/mcp-security))
- [x] Observability: metrics, health, audit logging
- [x] Rate limiting / quota management (built on [Bucket4j](https://bucket4j.com))
- [x] End-to-end integration tests and architecture tests (ArchUnit)
- [ ] Maven Central release

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
