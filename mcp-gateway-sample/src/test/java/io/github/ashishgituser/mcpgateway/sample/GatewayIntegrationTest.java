package io.github.ashishgituser.mcpgateway.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Drives the gateway end to end over real HTTP: two real in-process MCP servers stand in for
 * upstreams, and a real MCP client talks to the gateway's own endpoint, so this exercises tool
 * aggregation/namespacing, routing, policy enforcement and rate limiting together exactly as a
 * production caller would see them - not through mocks.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {

  private static final AtomicInteger alphaPingCalls = new AtomicInteger();
  private static final AtomicInteger alphaLimitedCalls = new AtomicInteger();
  private static final AtomicInteger betaEchoCalls = new AtomicInteger();
  private static final AtomicInteger betaSecretCalls = new AtomicInteger();

  private static WebServer alphaUpstream;
  private static WebServer betaUpstream;

  @LocalServerPort private int gatewayPort;

  private McpSyncClient client;

  @BeforeAll
  static void startUpstreams() {
    // "limited" only exists so the rate-limit test has a tool nobody else calls - the gateway's
    // context (and its rate limiter's buckets) is shared across every test method in this class.
    alphaUpstream =
        startUpstream(
            "ping",
            alphaPingCalls,
            "pong-from-alpha",
            Tool.builder("limited", Map.of("type", "object"))
                .description("used only by the rate-limit test")
                .build(),
            alphaLimitedCalls);
    betaUpstream =
        startUpstream(
            "echo",
            betaEchoCalls,
            "echo-from-beta",
            Tool.builder("secret", Map.of("type", "object"))
                .description("should be denied")
                .build(),
            betaSecretCalls);
  }

  @AfterAll
  static void stopUpstreams() {
    alphaUpstream.stop();
    betaUpstream.stop();
  }

  @DynamicPropertySource
  static void gatewayConfig(DynamicPropertyRegistry registry) {
    registry.add("mcp.gateway.servers[0].id", () -> "alpha");
    registry.add(
        "mcp.gateway.servers[0].endpoint",
        () -> "http://localhost:" + alphaUpstream.getPort() + "/mcp");
    registry.add("mcp.gateway.servers[1].id", () -> "beta");
    registry.add(
        "mcp.gateway.servers[1].endpoint",
        () -> "http://localhost:" + betaUpstream.getPort() + "/mcp");

    registry.add("mcp.gateway.policy.enabled", () -> "true");
    registry.add("mcp.gateway.policy.default-effect", () -> "ALLOW");
    registry.add("mcp.gateway.policy.rules[0].effect", () -> "DENY");
    registry.add("mcp.gateway.policy.rules[0].tools[0]", () -> "beta__secret");

    registry.add("mcp.gateway.rate-limit.enabled", () -> "true");
    registry.add("mcp.gateway.rate-limit.capacity", () -> "2");
    registry.add("mcp.gateway.rate-limit.refill-tokens", () -> "2");
    registry.add("mcp.gateway.rate-limit.refill-period", () -> "1m");
    registry.add("mcp.gateway.rate-limit.scope", () -> "PRINCIPAL_AND_TOOL");
  }

  @BeforeEach
  void connect() {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://localhost:" + gatewayPort + "/mcp")
            .build();
    client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
    client.initialize();
  }

  @AfterEach
  void disconnect() {
    client.closeGracefully();
  }

  @Test
  void aggregatesToolsFromBothUpstreamsUnderNamespacedNames() {
    List<String> toolNames = client.listTools().tools().stream().map(Tool::name).toList();
    assertThat(toolNames)
        .containsExactlyInAnyOrder("alpha__ping", "alpha__limited", "beta__echo", "beta__secret");
  }

  @Test
  void routesAnAllowedCallToTheOwningUpstreamWithTheOriginalToolName() {
    int before = alphaPingCalls.get();
    CallToolResult result = client.callTool(CallToolRequest.builder("alpha__ping").build());

    assertThat(text(result)).isEqualTo("pong-from-alpha");
    assertThat(alphaPingCalls.get()).isEqualTo(before + 1);
  }

  @Test
  void routesEachToolToItsOwnUpstreamWithoutCrossTalk() {
    int pingBefore = alphaPingCalls.get();
    int echoBefore = betaEchoCalls.get();

    assertThat(text(client.callTool(CallToolRequest.builder("alpha__ping").build())))
        .isEqualTo("pong-from-alpha");
    assertThat(text(client.callTool(CallToolRequest.builder("beta__echo").build())))
        .isEqualTo("echo-from-beta");

    assertThat(alphaPingCalls.get()).isEqualTo(pingBefore + 1);
    assertThat(betaEchoCalls.get()).isEqualTo(echoBefore + 1);
  }

  @Test
  void denyingPolicyRuleBlocksTheCallBeforeItEverReachesTheUpstream() {
    assertThatThrownBy(() -> client.callTool(CallToolRequest.builder("beta__secret").build()))
        .isNotNull();

    assertThat(betaSecretCalls.get()).isZero();
  }

  @Test
  void rateLimitRejectsCallsOnceTheConfiguredQuotaIsExhausted() {
    // capacity is 2, scoped per (principal, tool) - the first two calls consume the bucket.
    client.callTool(CallToolRequest.builder("alpha__limited").build());
    client.callTool(CallToolRequest.builder("alpha__limited").build());
    assertThat(alphaLimitedCalls.get()).isEqualTo(2);

    assertThatThrownBy(() -> client.callTool(CallToolRequest.builder("alpha__limited").build()))
        .isNotNull();

    // the third call never reached the upstream.
    assertThat(alphaLimitedCalls.get()).isEqualTo(2);
  }

  private static String text(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  private static WebServer startUpstream(
      String toolName,
      AtomicInteger callCount,
      String reply,
      Tool extraTool,
      AtomicInteger extraCallCount) {
    HttpServletStreamableServerTransportProvider transportProvider =
        HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();

    SyncToolSpecification mainSpec =
        SyncToolSpecification.builder()
            .tool(Tool.builder(toolName, Map.of("type", "object")).description("test tool").build())
            .callHandler(
                (exchange, request) -> {
                  callCount.incrementAndGet();
                  return CallToolResult.builder().addTextContent(reply).build();
                })
            .build();

    List<SyncToolSpecification> specs;
    if (extraTool == null) {
      specs = List.of(mainSpec);
    } else {
      SyncToolSpecification extraSpec =
          SyncToolSpecification.builder()
              .tool(extraTool)
              .callHandler(
                  (exchange, request) -> {
                    extraCallCount.incrementAndGet();
                    return CallToolResult.builder()
                        .addTextContent("should never be reached")
                        .build();
                  })
              .build();
      specs = List.of(mainSpec, extraSpec);
    }

    McpServer.sync(transportProvider)
        .serverInfo(toolName + "-upstream", "1.0.0")
        .tools(specs)
        .build();

    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
    WebServer webServer =
        factory.getWebServer(
            servletContext -> {
              var registration = servletContext.addServlet(toolName + "-mcp", transportProvider);
              registration.addMapping("/mcp");
              registration.setAsyncSupported(true);
              registration.setLoadOnStartup(1);
            });
    webServer.start();
    return webServer;
  }
}
