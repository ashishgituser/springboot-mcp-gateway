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
 * The counterpart to the filtering assertions in {@link GatewayIntegrationTest}: with {@code
 * mcp.gateway.policy.filter-tool-list=false} the denied tool stays in the published catalog and is
 * refused only when called. Deployments that already publish their tool inventory elsewhere can opt
 * into this; the pair of tests is what makes the difference between the two modes observable.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class UnfilteredToolCatalogIntegrationTest {

  private static final AtomicInteger openCalls = new AtomicInteger();
  private static final AtomicInteger shredCalls = new AtomicInteger();

  private static WebServer vaultUpstream;

  @LocalServerPort private int gatewayPort;

  private McpSyncClient client;

  @BeforeAll
  static void startUpstream() {
    vaultUpstream = startVault();
  }

  @AfterAll
  static void stopUpstream() {
    vaultUpstream.stop();
  }

  @DynamicPropertySource
  static void gatewayConfig(DynamicPropertyRegistry registry) {
    registry.add("mcp.gateway.servers[0].id", () -> "vault");
    registry.add(
        "mcp.gateway.servers[0].endpoint",
        () -> "http://localhost:" + vaultUpstream.getPort() + "/mcp");

    registry.add("mcp.gateway.policy.enabled", () -> "true");
    registry.add("mcp.gateway.policy.default-effect", () -> "ALLOW");
    registry.add("mcp.gateway.policy.filter-tool-list", () -> "false");
    registry.add("mcp.gateway.policy.rules[0].effect", () -> "DENY");
    registry.add("mcp.gateway.policy.rules[0].tools[0]", () -> "vault__shred");
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
  void deniedToolStaysVisibleWhenCatalogFilteringIsTurnedOff() {
    List<String> toolNames = client.listTools().tools().stream().map(Tool::name).toList();

    assertThat(toolNames).containsExactlyInAnyOrder("vault__open", "vault__shred");
  }

  @Test
  void visibleButDeniedToolIsStillRefusedBeforeReachingTheUpstream() {
    assertThatThrownBy(() -> client.callTool(CallToolRequest.builder("vault__shred").build()))
        .isNotNull();

    assertThat(shredCalls.get()).isZero();
  }

  private static WebServer startVault() {
    HttpServletStreamableServerTransportProvider transportProvider =
        HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();

    List<SyncToolSpecification> specs =
        List.of(toolSpec("open", openCalls), toolSpec("shred", shredCalls));

    McpServer.sync(transportProvider).serverInfo("vault-upstream", "1.0.0").tools(specs).build();

    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
    WebServer webServer =
        factory.getWebServer(
            servletContext -> {
              var registration = servletContext.addServlet("vault-mcp", transportProvider);
              registration.addMapping("/mcp");
              registration.setAsyncSupported(true);
              registration.setLoadOnStartup(1);
            });
    webServer.start();
    return webServer;
  }

  private static SyncToolSpecification toolSpec(String name, AtomicInteger callCount) {
    return SyncToolSpecification.builder()
        .tool(Tool.builder(name, Map.of("type", "object")).description("vault " + name).build())
        .callHandler(
            (exchange, request) -> {
              callCount.incrementAndGet();
              return CallToolResult.builder().addTextContent(name + "-done").build();
            })
        .build();
  }
}
