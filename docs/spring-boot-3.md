# Running on Spring Boot 3.x

The gateway is built and published against Spring Boot 4.1, but it runs on Spring Boot 3.x as well. Not by accident and not unverified — [a compatibility check](../compat/boot3) boots the published starter on Boot 3.5, drives it with a real MCP client, and fails the build if anything regresses.

## Why it works

Almost nothing in the gateway actually touches a Boot-4-only API. `mcp-gateway-core` has no Spring dependency at all (an ArchUnit test enforces that), and `mcp-gateway-autoconfigure` uses only APIs that are identical in Boot 3 and 4: `@AutoConfiguration`, the `@ConditionalOn*` family, `@ConfigurationProperties` record binding, `ServletRegistrationBean`, and Spring Security's `SecurityContextHolder`.

The one exception is the Actuator health indicator, which moved packages between Boot 3 and 4 (`org.springframework.boot.actuate.health` → `org.springframework.boot.health.contributor`). That autoconfiguration is guarded by `@ConditionalOnClass`, which Spring evaluates from bytecode metadata without loading the class — so on Boot 3 it is skipped cleanly rather than throwing.

## Setup

Two changes to the usual dependency block:

```xml
<dependency>
  <groupId>io.github.ashishgituser</groupId>
  <artifactId>mcp-gateway-spring-boot-starter</artifactId>
  <version>0.2.0</version>
  <exclusions>
    <!-- The MCP SDK ships a Jackson 3 binding by default; Boot 3 is on Jackson 2. -->
    <exclusion>
      <groupId>io.modelcontextprotocol.sdk</groupId>
      <artifactId>mcp-json-jackson3</artifactId>
    </exclusion>
  </exclusions>
</dependency>

<dependency>
  <groupId>io.modelcontextprotocol.sdk</groupId>
  <artifactId>mcp-json-jackson2</artifactId>
  <version>2.0.0</version>
</dependency>
```

Everything else — configuration properties, policy rules, quotas, audit — is identical to the Boot 4 setup in the [README](../README.md).

## What you lose

| | Boot 4.1 | Boot 3.5 |
|---|---|---|
| Routing, aggregation, namespacing | yes | yes |
| Authorization-aware discovery | yes | yes |
| Policy enforcement | yes | yes |
| Rate limiting (in-memory and Redis) | yes | yes |
| Audit logging | yes | yes |
| Metrics via Micrometer | yes | yes |
| Prompts and resources | yes | yes |
| `upstreams` health indicator | yes | **no** |

Only the `/actuator/health` upstream indicator is unavailable. Metrics still work, because Micrometer's `ObservationRegistry` is not a Boot-4-only API.

If you need per-upstream health on Boot 3, implement it yourself against Boot 3's `HealthIndicator` — the gateway exposes `UpstreamServer.ping()` and `available()`, which is all the bundled indicator uses.

## Verifying it yourself

```bash
mvn -B install -DskipTests
mvn -B -f compat/boot3/pom.xml compile exec:java
```

It prints each assertion and exits non-zero on failure. It runs in CI on every push.

## Support status

Boot 3.x is **supported but secondary**. The build, the test suite and the container images all target Boot 4.1; Boot 3 gets the compatibility check above and nothing more. If a future change makes the two genuinely diverge, this document is where that will be said plainly rather than quietly dropped.
