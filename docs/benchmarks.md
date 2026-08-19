# Benchmarks

"What does the gateway cost me?" is the first question anyone puts to a proxy, so here are measured numbers rather than an assurance.

## What is measured

One upstream MCP server, called two ways over the same streamable-HTTP transport: directly, and through the gateway. Only the **difference** between the two rows is meaningful — the absolute figures are dominated by HTTP and JSON on whatever machine the harness runs on, and yours will differ.

The gateway is doing real work during this, not passing through with everything switched off: policy enforcement, rate limiting and audit logging are all enabled. The upstream tool itself returns a constant, because any real work in the tool would swamp the difference being measured.

Reproduce it yourself:

```bash
mvn -pl mcp-gateway-sample -am test -Dtest=GatewayOverheadBenchmark -Dmcp.benchmark=true
```

The harness is [GatewayOverheadBenchmark](../mcp-gateway-sample/src/test/java/io/github/ashishgituser/mcpgateway/sample/GatewayOverheadBenchmark.java). It is excluded from the normal build on purpose: timings from a shared CI runner would be noise presented as fact.

## Results

`mcp-gateway 0.2.0` · Java 17.0.17 · Windows 11, 32 cores · loopback · 300 warmup + 3000 iterations

**Serial — one client, one call at a time**

| | p50 | p95 | p99 | throughput |
|---|---|---|---|---|
| direct to upstream | 0.57 ms | 1.18 ms | 1.70 ms | 1447/s |
| through gateway | 0.87 ms | 1.18 ms | 1.75 ms | 1109/s |
| **overhead** | **+0.30 ms** | **+0.01 ms** | **+0.05 ms** | |

**Concurrent — 16 clients**

| | p50 | p95 | p99 | throughput |
|---|---|---|---|---|
| direct to upstream | 0.62 ms | 1.45 ms | 16.55 ms | 11321/s |
| through gateway | 1.47 ms | 3.48 ms | 22.02 ms | 6710/s |
| **overhead** | **+0.84 ms** | **+2.03 ms** | **+5.48 ms** | |

## Reading these honestly

**Sub-millisecond at the median, and most of it is the extra hop.** A gateway means one more HTTP request and one more JSON round trip; policy evaluation is a glob match over an ordered rule list, quota is an in-memory token bucket, and audit is one log line. Against a real upstream — a database, a SaaS API, an LLM — a call costs tens or hundreds of milliseconds, and this overhead disappears into the noise.

**The concurrent numbers flatter the direct case.** Both sides ran on the same 32-core machine, so the gateway competed with the upstream and both clients for CPU. In a real deployment they are separate processes on separate hosts and the picture changes; treat the concurrent row as a worst case, not a forecast.

**p99 is noisy.** 16.55 ms at p99 for a *direct* call on loopback is GC and scheduler jitter, not the network. Don't read a capacity plan into a single run on a laptop.

**This is not a throughput ceiling.** The harness measures added latency, not how many requests the gateway can sustain. It uses a fixed client count and a trivial upstream; finding the saturation point of a real deployment needs a different experiment.

**Redis rate limiting is not in these numbers.** `store: REDIS` adds a Redis round trip per call. Measure it against your own Redis before assuming.
