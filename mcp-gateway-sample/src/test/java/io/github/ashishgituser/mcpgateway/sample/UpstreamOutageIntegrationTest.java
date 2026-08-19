package io.github.ashishgituser.mcpgateway.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ashishgituser.mcpgateway.core.routing.ToolRegistry;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A gateway fronting several upstreams must not be only as available as its least available one.
 * Here the second upstream is dead when the gateway boots: the gateway is expected to start anyway,
 * serve the healthy upstream, and fold the recovered one back into the catalog once it is up -
 * which is what makes rolling restarts and out-of-order deployments survivable.
 *
 * <p>Ordered because the second test deliberately changes the world the first one asserts on.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UpstreamOutageIntegrationTest {

  private static WebServer liveUpstream;
  private static WebServer recoveredUpstream;
  private static int deadUpstreamPort;

  @LocalServerPort private int gatewayPort;

  @Autowired private ToolRegistry toolRegistry;

  private McpSyncClient client;

  @BeforeAll
  static void startUpstreams() {
    liveUpstream = startUpstream("live-mcp", "ping", "pong-from-live", 0);
    // Bind a port, note it, then give it up: for the whole of the first test nothing is listening
    // there, which is exactly the "configured but not deployed yet" case.
    WebServer placeholder = startUpstream("placeholder-mcp", "later", "unused", 0);
    deadUpstreamPort = placeholder.getPort();
    placeholder.stop();
  }

  @AfterAll
  static void stopUpstreams() {
    liveUpstream.stop();
    if (recoveredUpstream != null) {
      recoveredUpstream.stop();
    }
  }

  @DynamicPropertySource
  static void gatewayConfig(DynamicPropertyRegistry registry) {
    registry.add("mcp.gateway.servers[0].id", () -> "live");
    registry.add(
        "mcp.gateway.servers[0].endpoint",
        () -> "http://localhost:" + liveUpstream.getPort() + "/mcp");
    registry.add("mcp.gateway.servers[1].id", () -> "dead");
    registry.add(
        "mcp.gateway.servers[1].endpoint", () -> "http://localhost:" + deadUpstreamPort + "/mcp");
    registry.add("mcp.gateway.servers[1].request-timeout", () -> "2s");
    // The test drives refreshes itself rather than waiting on the scheduler.
    registry.add("mcp.gateway.refresh-interval", () -> "0");
    // Startup probes tools, prompts and resources, so the dead upstream trips the breaker straight
    // away. A short window keeps the recovery case from waiting out the 30s default.
    registry.add("mcp.gateway.circuit-breaker.open-duration", () -> "1ms");
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
  @Order(1)
  void servesTheHealthyUpstreamWhileAnotherOneIsDown() {
    List<String> toolNames = client.listTools().tools().stream().map(Tool::name).toList();

    assertThat(toolNames).containsExactly("live__ping");
    assertThat(text(client.callTool(CallToolRequest.builder("live__ping").build())))
        .isEqualTo("pong-from-live");
  }

  @Test
  @Order(2)
  void refusesToolsOfTheUnreachableUpstreamInsteadOfHangingOnThem() {
    assertThatThrownBy(() -> client.callTool(CallToolRequest.builder("dead__later").build()))
        .isNotNull();
  }

  @Test
  @Order(3)
  void picksUpTheUpstreamOnceItComesBack() {
    recoveredUpstream =
        startUpstream("recovered-mcp", "later", "pong-from-recovered", deadUpstreamPort);

    toolRegistry.refresh();

    List<String> toolNames = client.listTools().tools().stream().map(Tool::name).toList();
    assertThat(toolNames).containsExactlyInAnyOrder("live__ping", "dead__later");
    assertThat(text(client.callTool(CallToolRequest.builder("dead__later").build())))
        .isEqualTo("pong-from-recovered");
  }

  private static String text(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  private static WebServer startUpstream(
      String servletName, String toolName, String reply, int port) {
    HttpServletStreamableServerTransportProvider transportProvider =
        HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();

    SyncToolSpecification spec =
        SyncToolSpecification.builder()
            .tool(Tool.builder(toolName, Map.of("type", "object")).description("test tool").build())
            .callHandler(
                (exchange, request) -> CallToolResult.builder().addTextContent(reply).build())
            .build();

    McpServer.sync(transportProvider).serverInfo(servletName, "1.0.0").tools(List.of(spec)).build();

    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(port);
    WebServer webServer =
        factory.getWebServer(
            servletContext -> {
              var registration = servletContext.addServlet(servletName, transportProvider);
              registration.addMapping("/mcp");
              registration.setAsyncSupported(true);
              registration.setLoadOnStartup(1);
            });
    webServer.start();
    return webServer;
  }
}
