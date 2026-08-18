# Spring Boot MCP Gateway

A Spring Boot starter that puts a single gateway in front of multiple [MCP](https://modelcontextprotocol.io) (Model Context Protocol) servers: one endpoint for clients, with routing, auth/policy enforcement, observability and rate limiting handled centrally instead of in every downstream server.

> **Status:** early development. Multi-server routing/aggregation is implemented; policy, observability and rate limiting are being built module by module. Not yet published to Maven Central — see [CHANGELOG.md](CHANGELOG.md) for progress.

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

## Roadmap

- [x] Multi-module project scaffolding
- [x] Multi-server routing/aggregation
- [ ] Auth & policy enforcement (built on [mcp-security](https://github.com/spring-ai-community/mcp-security))
- [ ] Observability: metrics, tracing, audit logging
- [ ] Rate limiting / quota management
- [ ] Maven Central release

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
