# Security model

This describes what the gateway defends against and, just as importantly, what it does not. For reporting a vulnerability see [SECURITY.md](../SECURITY.md).

## Where the trust boundary is

```
   untrusted            trusted-ish              trusted
   ─────────            ───────────              ───────
   AI agent    ──────►  MCP Gateway    ──────►   MCP servers
   (may be              (authorizes,             (assumed to be
    prompt-              meters, audits)          your own)
    injected)
```

The gateway's job is the middle box. It assumes:

- **The agent is not trustworthy.** An LLM-driven client can be steered by whatever text it just read. It may attempt tools nobody intended it to reach. That is the primary threat.
- **The upstream MCP servers are yours.** The gateway does not sandbox them or validate their responses. Fronting a third-party MCP server is possible but out of scope for the guarantees below.
- **Authentication happens elsewhere.** The gateway authorizes; it never authenticates. See [Identity](#identity).

## Identity

The gateway has no authentication logic of its own, by design — you already have an identity provider and it is not the gateway's business to become a second one. Add [mcp-security](https://github.com/spring-ai-community/mcp-security) (`org.springaicommunity:mcp-server-security-spring-boot`) for OAuth2 resource-server or API-key authentication, or authenticate in your application's own filter chain.

The gateway then reads whatever was authenticated:

- Spring Security on the classpath → the caller and its authorities come from the `SecurityContext`.
- Otherwise → the servlet principal, **with no roles**. Role-based policy rules can therefore never match, which is the safe failure: with `default-effect: DENY` an unauthenticated deployment denies everything rather than quietly allowing it.

Callers with no principal at all resolve to `anonymous`. That is a real identity for policy purposes — you can write rules about it — not a bypass.

## Threats and what covers them

| Threat | Covered by | Residual risk |
|---|---|---|
| A prompt-injected agent calls a destructive tool | Policy engine, evaluated before routing. `default-effect: DENY` means anything not explicitly allowed is refused. | Only as good as the rules you write. A broad `tools: ["*"]` allow gives it all back. |
| An agent discovers tools it should not know exist | Authorization-aware discovery: `tools/list` is filtered by the same engine that gates calls, so denied tools are never named, described, or schema-disclosed. On by default. | Turning off `filter-tool-list` reinstates the disclosure. |
| A compromised or runaway agent exhausts an expensive backend | Rate limiting, checked before the call is forwarded. Scope per principal, per tool, or per pair. | In-memory: quota is per gateway replica, so N replicas means N× the quota. Supply your own `RateLimiter` for a shared store. |
| Credentials or personal data leaking into logs | Argument capture is off by default; when enabled, matching keys are masked before the event reaches any logger. | The default pattern list is broad but not exhaustive — set `mcp.gateway.audit.redact` for your own field names. |
| No evidence after an incident | One structured JSON audit line per call — principal, tool, outcome, duration, reason — on a dedicated logger, written for allowed *and* refused calls. | Retention and tamper-resistance are your logging pipeline's job, not the gateway's. |
| A tool name collision routes a call to the wrong server | Namespacing: every tool is published as `<serverId>__<toolName>` and resolved back through the registry. | — |
| One upstream failing takes the whole gateway down | Lazy connections plus a per-upstream circuit breaker; catalog refresh skips unreachable servers. | A *slow* upstream still consumes a request thread until its timeout — set per-server `request-timeout`. |
| An agent reaches an upstream directly, bypassing the gateway | **Not covered.** | Network policy is yours: the MCP servers must only be reachable from the gateway. See below. |

## What the gateway does not do

Being explicit about this matters more than the feature list:

- **It is not a network control.** If an agent can open a socket to the upstream MCP server, none of the above applies. The gateway is only a chokepoint if your network makes it one — put the upstreams on an internal network and expose only the gateway.
- **It does not inspect tool arguments for attacks.** No SQL parsing, no path traversal checks, no prompt-injection detection. Policy is about *which tool*, not *what it was asked to do*. Argument-level constraints are a future consideration.
- **It does not validate upstream responses.** A malicious upstream can return anything it likes to the agent.
- **It does not encrypt or store secrets.** Upstream endpoints come from configuration; use your platform's secret management.
- **It does not authenticate.** See [Identity](#identity).

## Hardening a deployment

1. `mcp.gateway.policy.enabled: true` with `default-effect: DENY`. Anything else means an unlisted tool is reachable.
2. Leave `filter-tool-list: true`. There is rarely a reason for an agent to know about a tool it cannot call.
3. Put the upstream MCP servers on an internal network reachable only by the gateway.
4. Add `mcp-security` and require authentication, so policy has real principals and roles to act on.
5. Turn on rate limiting even if the numbers are generous — it is the only bound on a looping agent.
6. Route the `io.github.ashishgituser.mcpgateway.audit` logger to its own append-only sink.
7. If you enable `include-arguments`, review the `redact` list against your own tools' parameter names before it reaches production.
