package io.github.ashishgituser.mcpgateway.demo;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;

/**
 * A stand-in MCP server for the quickstart. Its identity and tool list come entirely from
 * configuration, so one image can play the part of a GitHub server, a database server, or whatever
 * else the compose file needs. Each tool echoes a canned result — the point of the quickstart is
 * the gateway in front, not what the upstreams do.
 */
@SpringBootApplication
@EnableConfigurationProperties(DemoUpstreamProperties.class)
public class DemoUpstreamApplication {

  public static void main(String[] args) {
    SpringApplication.run(DemoUpstreamApplication.class, args);
  }

  @Bean
  public HttpServletStreamableServerTransportProvider transportProvider() {
    return HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
  }

  @Bean
  public McpSyncServer mcpServer(
      HttpServletStreamableServerTransportProvider transportProvider,
      DemoUpstreamProperties properties) {
    List<SyncToolSpecification> tools =
        properties.tools().stream().map(DemoUpstreamApplication::toSpecification).toList();
    return McpServer.sync(transportProvider)
        .serverInfo(properties.name(), "1.0.0")
        .tools(tools)
        .build();
  }

  @Bean
  @DependsOn("mcpServer")
  public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
      HttpServletStreamableServerTransportProvider transportProvider) {
    return new ServletRegistrationBean<>(transportProvider, "/mcp");
  }

  private static SyncToolSpecification toSpecification(DemoUpstreamProperties.ToolSpec spec) {
    Tool tool =
        Tool.builder(spec.name(), Map.of("type", "object")).description(spec.description()).build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler(
            (exchange, request) -> CallToolResult.builder().addTextContent(spec.result()).build())
        .build();
  }
}
