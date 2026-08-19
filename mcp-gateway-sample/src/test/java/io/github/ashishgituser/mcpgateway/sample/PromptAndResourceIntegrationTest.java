package io.github.ashishgituser.mcpgateway.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.time.Duration;
import java.util.List;
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
 * Tools are not the only way to reach data through MCP. If the gateway governed tool calls but let
 * prompts and resources through ungoverned, an agent denied {@code docs__internalReview} could just
 * read the same thing as a resource. These assertions are what make that impossible: prompts are
 * namespaced and filtered like tools, resources are matched on their URI, and a denial is enforced
 * before the upstream is touched.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PromptAndResourceIntegrationTest {

  private static final AtomicInteger secretReads = new AtomicInteger();

  private static WebServer docsUpstream;

  @LocalServerPort private int gatewayPort;

  private McpSyncClient client;

  @BeforeAll
  static void startUpstream() {
    docsUpstream = startDocs();
  }

  @AfterAll
  static void stopUpstream() {
    docsUpstream.stop();
  }

  @DynamicPropertySource
  static void gatewayConfig(DynamicPropertyRegistry registry) {
    registry.add("mcp.gateway.servers[0].id", () -> "docs");
    registry.add(
        "mcp.gateway.servers[0].endpoint",
        () -> "http://localhost:" + docsUpstream.getPort() + "/mcp");

    registry.add("mcp.gateway.policy.enabled", () -> "true");
    registry.add("mcp.gateway.policy.default-effect", () -> "ALLOW");
    registry.add("mcp.gateway.policy.rules[0].effect", () -> "DENY");
    registry.add("mcp.gateway.policy.rules[0].tools[0]", () -> "docs__internalReview");
    registry.add("mcp.gateway.policy.rules[0].tools[1]", () -> "file:///docs/secret.md");
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
  void aggregatesPromptsUnderNamespacedNamesAndHidesDeniedOnes() {
    List<String> promptNames = client.listPrompts().prompts().stream().map(Prompt::name).toList();

    assertThat(promptNames).contains("docs__summarize");
    assertThat(promptNames).doesNotContain("docs__internalReview");
  }

  @Test
  void routesAnAllowedPromptToTheUpstreamUnderItsOriginalName() {
    GetPromptResult result = client.getPrompt(new GetPromptRequest("docs__summarize", null, null));

    assertThat(result.messages()).hasSize(1);
    assertThat(((TextContent) result.messages().get(0).content()).text())
        .isEqualTo("summary-of-docs");
  }

  @Test
  void deniedPromptIsRefusedNotJustHidden() {
    assertThatThrownBy(
            () -> client.getPrompt(new GetPromptRequest("docs__internalReview", null, null)))
        .isNotNull();
  }

  @Test
  void aggregatesResourcesAndHidesDeniedUris() {
    List<String> uris = client.listResources().resources().stream().map(Resource::uri).toList();

    assertThat(uris).contains("file:///docs/readme.md");
    assertThat(uris).doesNotContain("file:///docs/secret.md");
  }

  @Test
  void readsAnAllowedResourceThroughTheGateway() {
    ReadResourceResult result =
        client.readResource(new ReadResourceRequest("file:///docs/readme.md", null));

    assertThat(((TextResourceContents) result.contents().get(0)).text()).isEqualTo("readme-body");
  }

  @Test
  void deniedResourceReadNeverReachesTheUpstream() {
    assertThatThrownBy(
            () -> client.readResource(new ReadResourceRequest("file:///docs/secret.md", null)))
        .isNotNull();

    assertThat(secretReads.get()).isZero();
  }

  private static WebServer startDocs() {
    HttpServletStreamableServerTransportProvider transportProvider =
        HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();

    SyncPromptSpecification summarize =
        new SyncPromptSpecification(
            new Prompt("summarize", null, "Summarize the docs", List.of(), null, null),
            (exchange, request) ->
                new GetPromptResult(
                    "summary",
                    List.of(new PromptMessage(Role.USER, new TextContent("summary-of-docs"))),
                    null));
    SyncPromptSpecification internalReview =
        new SyncPromptSpecification(
            new Prompt("internalReview", null, "Internal only", List.of(), null, null),
            (exchange, request) ->
                new GetPromptResult(
                    "internal",
                    List.of(new PromptMessage(Role.USER, new TextContent("should-not-be-reached"))),
                    null));

    SyncResourceSpecification readme =
        new SyncResourceSpecification(
            resource("file:///docs/readme.md", "readme"),
            (exchange, request) ->
                new ReadResourceResult(
                    List.of(
                        new TextResourceContents(
                            "file:///docs/readme.md", "text/markdown", "readme-body", null)),
                    null));
    SyncResourceSpecification secret =
        new SyncResourceSpecification(
            resource("file:///docs/secret.md", "secret"),
            (exchange, request) -> {
              secretReads.incrementAndGet();
              return new ReadResourceResult(
                  List.of(
                      new TextResourceContents(
                          "file:///docs/secret.md", "text/markdown", "secret-body", null)),
                  null);
            });

    McpServer.sync(transportProvider)
        .serverInfo("docs-upstream", "1.0.0")
        .prompts(List.of(summarize, internalReview))
        .resources(readme, secret)
        .build();

    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
    WebServer webServer =
        factory.getWebServer(
            servletContext -> {
              var registration = servletContext.addServlet("docs-mcp", transportProvider);
              registration.addMapping("/mcp");
              registration.setAsyncSupported(true);
              registration.setLoadOnStartup(1);
            });
    webServer.start();
    return webServer;
  }

  private static Resource resource(String uri, String name) {
    return new Resource(uri, name, null, null, "text/markdown", null, null, null, null);
  }
}
