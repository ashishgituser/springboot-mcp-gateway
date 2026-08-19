# Architecture

## Modules

```
mcp-gateway-core            routing, policy, visibility, rate-limit SPI,
                            audit model, upstream connections + resilience
        ▲                   (no Spring, no Servlet - enforced by ArchUnit)
        │
mcp-gateway-autoconfigure   @AutoConfiguration, mcp.gateway.* binding,
        ▲                   Spring Security bridge, health indicator
        │
mcp-gateway-spring-boot-starter   the dependency applications add
        ▲
        ├── mcp-gateway-server     standalone container distribution
        └── mcp-gateway-sample     example app + end-to-end tests
```

`mcp-gateway-core` deliberately knows nothing about Spring. That is not purity for its own sake: it is what lets the routing and policy logic be unit-tested without a container, and it is what will make a Spring Boot 3.x build tractable, since only the autoconfigure module would need a second variant.

## Why the gateway owns its MCP data plane

The MCP Java SDK's `McpServer` answers `tools/list` from a list fixed when the server was built — the same list for every caller, with no per-request hook. A gateway cannot work that way: its catalog is assembled from several upstreams and the slice a caller may see is a policy decision made per request.

So instead of registering itself as a tool server, the gateway builds its own JSON-RPC handler map and installs it on the transport provider:

```java
transportProvider.setSessionFactory(
    new DefaultMcpStreamableServerSessionFactory(
        requestTimeout, initRequestHandler, requestHandlers, notificationHandlers, onClose));
```

`GatewayServerHandlers` supplies `ping`, `tools/list`, `tools/call`, `prompts/list`, `prompts/get`, `resources/list`, `resources/templates/list` and `resources/read`; `GatewayInitRequestHandler` negotiates the protocol version. Everything else about the transport — sessions, SSE framing, resumability — is still the SDK's.

## Request lifecycle

A `tools/call` from a client to an upstream, in order:

1. **HTTP request** arrives at the gateway's MCP servlet (`mcp.gateway.mcp-endpoint`, default `/mcp`).
2. **Authentication** has already happened — in the application's own filter chain, or in `mcp-security` if that is what is authenticating callers. The gateway never authenticates anything itself.
3. **Principal resolution.** `PrincipalContextExtractor` reads the caller out of the `SecurityContext` (or the servlet principal when Spring Security is absent) and puts a `GatewayPrincipal` into the MCP transport context, where the handler can reach it.
4. **Handler dispatch.** The session routes `tools/call` to `GatewayServerHandlers`, which recovers the principal and hands off to `GatewayRouter`, offloading to the elastic scheduler because the upstream client blocks.
5. **Policy evaluation.** `PolicyEngine.evaluate(principal, namespacedToolName)`. Deliberately before the registry lookup, so a denial never depends on which tools happen to be registered at that moment. Denial → `PolicyDeniedException`.
6. **Quota.** `RateLimiter.checkLimit(principal, tool)`. Over quota → `RateLimitExceededException`. Both of these happen before anything is forwarded, so a rejected call costs an upstream nothing.
7. **Tool resolution.** `ToolRegistry.find(namespacedName)` maps `github__searchCode` back to the `github` upstream and the original name `searchCode`.
8. **Circuit check and forward.** `UpstreamServer` refuses immediately if its breaker is open; otherwise it connects (lazily, on first use) and forwards the call with the original tool name and the caller's arguments.
9. **Observation.** The whole span is recorded as `mcp.gateway.tool.call` with `tool.name` and `outcome`, whichever way it ended.
10. **Audit.** One `AuditEvent` per call — principal, tool, outcome, duration, reason, and the arguments if capture is on, masked by `ArgumentRedactor` on the way.
11. **Response** returns to the client over the same MCP session.

A `tools/list` follows steps 1–4, then asks `GatewayRouter.listTools(principal)`, which filters `ToolRegistry.allTools()` through `ToolVisibility`. With the default `ToolVisibility.governedBy(policyEngine)`, that is the same evaluation step 5 would make — which is precisely why the two can never disagree.

## Namespacing

Upstream tools and prompts are published as `<serverId>__<name>`. Two servers can both expose `search` without colliding, policy rules can target a whole server with `github__*`, and the mapping back to the original name is what the router forwards. The separator is a double underscore because MCP names are conventionally `[a-zA-Z0-9_-]`, so it stays a legal name.

Resources are the exception: they are identified by a URI, the URI is meaningful to the client, and there is no namespacing scheme the spec blesses — rewriting it would break clients that resolve it. So resource URIs pass through unchanged and `ResourceRegistry` remembers which upstream owns each one. Two upstreams publishing the same URI is a configuration mistake, so it is logged and the first registration wins rather than flip-flopping on each refresh.

Policy sees all three under one namespace: the namespaced tool name, the namespaced prompt name, or the resource URI. That is what stops a rule written for a tool being sidestepped by asking for the same content as a resource.

## Catalog lifecycle

The catalog is built at startup and rebuilt by `CatalogRefresher` every `mcp.gateway.refresh-interval` (default 60s):

- An upstream that cannot be reached is logged and skipped, not fatal. Its tools leave the catalog while it is down, so the gateway never advertises something it cannot route.
- An upstream that appears later joins on a subsequent pass. This is what makes deployment order irrelevant.
- When the published set of names changes, connected clients get `notifications/tools/list_changed`.

## Upstream resilience

`UpstreamServer` opens its MCP session on first use rather than at startup, and wraps every upstream operation in a `CircuitBreaker`:

- Three consecutive transport failures open the breaker for 30 seconds.
- The first call after that window is a probe: success closes the breaker, failure restarts the window.
- `McpError` — a protocol-level error — counts as **success** for the breaker. The server answered; it just answered no. Treating that as a connectivity failure would take a healthy server out of rotation because one tool is unhappy.

The breaker is deliberately small and dependency-free. A gateway fronting a handful of upstreams does not need sliding windows or bulkheads, and `mcp-gateway-core` stays free of a resilience library.

## Extension points

Every one of these is a `@ConditionalOnMissingBean`, so defining your own bean replaces the default without any configuration flag:

| Interface | Replace it to |
|---|---|
| `PolicyEngine` | evaluate authorization against your own system (OPA, an internal service, a database) |
| `ToolVisibility` | filter discovery on something other than the policy engine |
| `RateLimiter` | meter against something other than the bundled in-memory and Redis stores |
| `AuditLogger` | ship audit events somewhere other than SLF4J |
| `PrincipalResolver` | derive the caller from something other than Spring Security |
