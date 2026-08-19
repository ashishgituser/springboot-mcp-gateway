package io.github.ashishgituser.mcpgateway.sample;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Measures what the gateway costs, by calling one upstream two ways over the same transport: once
 * directly, once through the gateway. Only the difference between the two is meaningful — the
 * absolute numbers are dominated by HTTP and JSON on whatever machine this runs on.
 *
 * <p>Not part of the normal build: timings from a shared CI runner would be noise presented as
 * fact. Run it deliberately and publish what it prints:
 *
 * <pre>
 * mvn -pl mcp-gateway-sample test -Dtest=GatewayOverheadBenchmark -Dmcp.benchmark=true
 * </pre>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "mcp.benchmark", matches = "true")
class GatewayOverheadBenchmark {

  private static final int WARMUP = 300;
  private static final int ITERATIONS = 3000;
  private static final int CONCURRENCY = 16;

  private static WebServer upstream;

  @LocalServerPort private int gatewayPort;

  @BeforeAll
  static void startUpstream() {
    upstream = startEchoUpstream();
  }

  @AfterAll
  static void stopUpstream() {
    upstream.stop();
  }

  @DynamicPropertySource
  static void gatewayConfig(DynamicPropertyRegistry registry) {
    registry.add("mcp.gateway.servers[0].id", () -> "bench");
    registry.add(
        "mcp.gateway.servers[0].endpoint", () -> "http://localhost:" + upstream.getPort() + "/mcp");
    // Policy, quota and audit all on: the point is to measure the gateway doing its job, not a
    // pass-through with every feature disabled.
    registry.add("mcp.gateway.policy.enabled", () -> "true");
    registry.add("mcp.gateway.policy.default-effect", () -> "ALLOW");
    registry.add("mcp.gateway.rate-limit.enabled", () -> "true");
    registry.add("mcp.gateway.rate-limit.capacity", () -> "1000000");
    registry.add("mcp.gateway.rate-limit.refill-tokens", () -> "1000000");
    registry.add("mcp.gateway.rate-limit.refill-period", () -> "1s");
  }

  @Test
  void reportsGatewayOverhead() throws Exception {
    String directUrl = "http://localhost:" + upstream.getPort() + "/mcp";
    String gatewayUrl = "http://localhost:" + gatewayPort + "/mcp";

    Stats directSerial = measureSerial(directUrl, "echo");
    Stats gatewaySerial = measureSerial(gatewayUrl, "bench__echo");
    Stats directConcurrent = measureConcurrent(directUrl, "echo");
    Stats gatewayConcurrent = measureConcurrent(gatewayUrl, "bench__echo");

    System.out.println();
    System.out.println("=== MCP gateway overhead ===");
    System.out.printf(
        "java=%s  os=%s  cores=%d%n",
        System.getProperty("java.version"),
        System.getProperty("os.name"),
        Runtime.getRuntime().availableProcessors());
    System.out.printf(
        "warmup=%d  iterations=%d  concurrency=%d%n", WARMUP, ITERATIONS, CONCURRENCY);
    System.out.println();
    report("serial, 1 client", directSerial, gatewaySerial);
    report("concurrent, " + CONCURRENCY + " clients", directConcurrent, gatewayConcurrent);
  }

  private static void report(String label, Stats direct, Stats gateway) {
    System.out.println(label);
    System.out.printf("  %-12s %8s %8s %8s %10s%n", "", "p50", "p95", "p99", "throughput");
    System.out.printf(
        "  %-12s %7.2fms %7.2fms %7.2fms %8.0f/s%n",
        "direct", direct.p50(), direct.p95(), direct.p99(), direct.throughput());
    System.out.printf(
        "  %-12s %7.2fms %7.2fms %7.2fms %8.0f/s%n",
        "gateway", gateway.p50(), gateway.p95(), gateway.p99(), gateway.throughput());
    System.out.printf(
        "  %-12s %+7.2fms %+7.2fms %+7.2fms%n",
        "overhead",
        gateway.p50() - direct.p50(),
        gateway.p95() - direct.p95(),
        gateway.p99() - direct.p99());
    System.out.println();
  }

  private static Stats measureSerial(String url, String toolName) {
    McpSyncClient client = connect(url);
    try {
      CallToolRequest request = CallToolRequest.builder(toolName).build();
      for (int i = 0; i < WARMUP; i++) {
        client.callTool(request);
      }
      long[] samples = new long[ITERATIONS];
      long startedAt = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        long callStart = System.nanoTime();
        client.callTool(request);
        samples[i] = System.nanoTime() - callStart;
      }
      return new Stats(samples, System.nanoTime() - startedAt);
    } finally {
      client.closeGracefully();
    }
  }

  private static Stats measureConcurrent(String url, String toolName) throws Exception {
    List<McpSyncClient> clients = new ArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
    try {
      for (int i = 0; i < CONCURRENCY; i++) {
        clients.add(connect(url));
      }
      CallToolRequest request = CallToolRequest.builder(toolName).build();
      int perClient = ITERATIONS / CONCURRENCY;

      for (McpSyncClient client : clients) {
        for (int i = 0; i < WARMUP / CONCURRENCY; i++) {
          client.callTool(request);
        }
      }

      long startedAt = System.nanoTime();
      List<Future<long[]>> futures = new ArrayList<>();
      for (McpSyncClient client : clients) {
        futures.add(
            pool.submit(
                () -> {
                  long[] samples = new long[perClient];
                  for (int i = 0; i < perClient; i++) {
                    long callStart = System.nanoTime();
                    client.callTool(request);
                    samples[i] = System.nanoTime() - callStart;
                  }
                  return samples;
                }));
      }
      long[] all = new long[perClient * CONCURRENCY];
      int offset = 0;
      for (Future<long[]> future : futures) {
        long[] samples = future.get(5, TimeUnit.MINUTES);
        System.arraycopy(samples, 0, all, offset, samples.length);
        offset += samples.length;
      }
      return new Stats(all, System.nanoTime() - startedAt);
    } finally {
      pool.shutdownNow();
      clients.forEach(McpSyncClient::closeGracefully);
    }
  }

  private static McpSyncClient connect(String url) {
    McpSyncClient client =
        McpClient.sync(HttpClientStreamableHttpTransport.builder(url).build())
            .requestTimeout(Duration.ofSeconds(30))
            .build();
    client.initialize();
    return client;
  }

  private static WebServer startEchoUpstream() {
    HttpServletStreamableServerTransportProvider transportProvider =
        HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();

    // The tool does nothing on purpose: any real work would swamp the difference being measured.
    SyncToolSpecification echo =
        SyncToolSpecification.builder()
            .tool(Tool.builder("echo", Map.of("type", "object")).description("benchmark").build())
            .callHandler(
                (exchange, request) -> CallToolResult.builder().addTextContent("ok").build())
            .build();

    McpServer.sync(transportProvider)
        .serverInfo("bench-upstream", "1.0.0")
        .tools(List.of(echo))
        .build();

    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
    WebServer webServer =
        factory.getWebServer(
            servletContext -> {
              var registration = servletContext.addServlet("bench-mcp", transportProvider);
              registration.addMapping("/mcp");
              registration.setAsyncSupported(true);
              registration.setLoadOnStartup(1);
            });
    webServer.start();
    return webServer;
  }

  private static final class Stats {

    private final long[] sortedNanos;
    private final long wallClockNanos;

    Stats(long[] samples, long wallClockNanos) {
      this.sortedNanos = samples.clone();
      Arrays.sort(this.sortedNanos);
      this.wallClockNanos = wallClockNanos;
    }

    double p50() {
      return percentile(0.50);
    }

    double p95() {
      return percentile(0.95);
    }

    double p99() {
      return percentile(0.99);
    }

    double throughput() {
      return sortedNanos.length / (wallClockNanos / 1_000_000_000d);
    }

    private double percentile(double fraction) {
      int index =
          (int) Math.min(sortedNanos.length - 1L, Math.round(fraction * sortedNanos.length));
      return sortedNanos[index] / 1_000_000d;
    }
  }
}
