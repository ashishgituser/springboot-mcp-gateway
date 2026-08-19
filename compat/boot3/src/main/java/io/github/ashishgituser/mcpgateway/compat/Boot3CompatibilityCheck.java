package io.github.ashishgituser.mcpgateway.compat;

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
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Proves the starter still works on Spring Boot 3.x.
 *
 * <p>The gateway is built and published against Boot 4, but nothing in it except the Actuator
 * health indicator actually depends on Boot 4 APIs — and that one is guarded by
 * {@code @ConditionalOnClass}, so it is skipped rather than exploding. That makes Boot 3 support
 * real, and a claim like that rots the moment nobody checks it. So this boots the published starter
 * on Boot 3, drives it end to end with a real MCP client, and exits non-zero if anything regresses.
 *
 * <p>Deliberately outside the main reactor: it needs a different Spring Boot parent, and mixing two
 * Boot versions in one build is more trouble than a separate {@code mvn -f compat/boot3/pom.xml} in
 * CI.
 */
@SpringBootApplication
public class Boot3CompatibilityCheck {

  public static void main(String[] args) {
    WebServer upstream = startUpstream();
    System.out.println("upstream on " + upstream.getPort());

    ConfigurableApplicationContext context =
        new SpringApplication(Boot3CompatibilityCheck.class)
            .run(
                "--server.port=18080",
                "--mcp.gateway.servers[0].id=alpha",
                "--mcp.gateway.servers[0].endpoint=http://localhost:"
                    + upstream.getPort()
                    + "/mcp",
                "--mcp.gateway.policy.enabled=true",
                "--mcp.gateway.policy.default-effect=ALLOW",
                "--mcp.gateway.policy.rules[0].effect=DENY",
                "--mcp.gateway.policy.rules[0].tools[0]=alpha__secret");

    int failures = 0;
    try {
      McpSyncClient client =
          McpClient.sync(
                  HttpClientStreamableHttpTransport.builder("http://localhost:18080/mcp").build())
              .requestTimeout(Duration.ofSeconds(10))
              .build();
      client.initialize();

      List<String> tools = client.listTools().tools().stream().map(Tool::name).toList();
      System.out.println("RESULT tools=" + tools);
      failures += check("aggregates and namespaces", tools.contains("alpha__ping"));
      failures += check("hides denied tool", !tools.contains("alpha__secret"));

      CallToolResult result = client.callTool(CallToolRequest.builder("alpha__ping").build());
      String text = ((TextContent) result.content().get(0)).text();
      System.out.println("RESULT call=" + text);
      failures += check("routes an allowed call", "pong".equals(text));

      boolean denied = false;
      try {
        client.callTool(CallToolRequest.builder("alpha__secret").build());
      } catch (RuntimeException e) {
        denied = true;
      }
      failures += check("refuses a denied call", denied);

      client.closeGracefully();
    } finally {
      context.close();
      upstream.stop();
    }
    System.out.println(failures == 0 ? "BOOT 3 COMPATIBILITY: PASS" : "BOOT 3 COMPATIBILITY: FAIL (" + failures + " checks)");
    System.exit(failures == 0 ? 0 : 1);
  }

  private static int check(String what, boolean ok) {
    System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    return ok ? 0 : 1;
  }

  private static WebServer startUpstream() {
    HttpServletStreamableServerTransportProvider provider =
        HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
    McpServer.sync(provider)
        .serverInfo("alpha-upstream", "1.0.0")
        .tools(List.of(tool("ping", "pong"), tool("secret", "leaked")))
        .build();
    WebServer webServer =
        new TomcatServletWebServerFactory(0)
            .getWebServer(
                servletContext -> {
                  var registration = servletContext.addServlet("alpha-mcp", provider);
                  registration.addMapping("/mcp");
                  registration.setAsyncSupported(true);
                  registration.setLoadOnStartup(1);
                });
    webServer.start();
    return webServer;
  }

  private static SyncToolSpecification tool(String name, String reply) {
    return SyncToolSpecification.builder()
        .tool(Tool.builder(name, Map.of("type", "object")).description("compat check").build())
        .callHandler((exchange, request) -> CallToolResult.builder().addTextContent(reply).build())
        .build();
  }
}
