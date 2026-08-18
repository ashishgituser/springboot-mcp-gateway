package io.github.ashishgituser.mcpgateway.autoconfigure;

import io.github.ashishgituser.mcpgateway.core.routing.GatewayRouter;
import io.github.ashishgituser.mcpgateway.core.routing.ToolRegistry;
import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamClientFactory;
import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServerDefinition;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.Servlet;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;

@AutoConfiguration
@ConditionalOnClass(Servlet.class)
@ConditionalOnProperty(
    prefix = "mcp.gateway",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(McpGatewayProperties.class)
public class McpGatewayAutoConfiguration {

  @Bean
  public UpstreamClientFactory upstreamClientFactory() {
    return new UpstreamClientFactory();
  }

  @Bean
  public List<UpstreamServer> upstreamServers(
      McpGatewayProperties properties, UpstreamClientFactory clientFactory) {
    return properties.servers().stream()
        .map(
            server ->
                new UpstreamServerDefinition(
                    server.id(), server.endpoint(), server.requestTimeout()))
        .map(clientFactory::connect)
        .toList();
  }

  @Bean
  public ToolRegistry toolRegistry(List<UpstreamServer> upstreamServers) {
    ToolRegistry registry = new ToolRegistry(upstreamServers);
    registry.refresh();
    return registry;
  }

  @Bean
  public GatewayRouter gatewayRouter(ToolRegistry toolRegistry) {
    return new GatewayRouter(toolRegistry);
  }

  @Bean
  public HttpServletStreamableServerTransportProvider mcpTransportProvider(
      McpGatewayProperties properties) {
    return HttpServletStreamableServerTransportProvider.builder()
        .mcpEndpoint(properties.mcpEndpoint())
        .build();
  }

  @Bean
  public McpSyncServer mcpSyncServer(
      HttpServletStreamableServerTransportProvider transportProvider,
      ToolRegistry toolRegistry,
      GatewayRouter gatewayRouter) {
    List<SyncToolSpecification> toolSpecifications =
        toolRegistry.allTools().stream()
            .map(
                tool ->
                    SyncToolSpecification.builder()
                        .tool(tool)
                        .callHandler((exchange, request) -> gatewayRouter.callTool(request))
                        .build())
            .toList();
    return McpServer.sync(transportProvider)
        .serverInfo("mcp-gateway", "0.1.0")
        .tools(toolSpecifications)
        .build();
  }

  @Bean
  @DependsOn("mcpSyncServer")
  public ServletRegistrationBean<HttpServletStreamableServerTransportProvider>
      mcpServletRegistration(
          HttpServletStreamableServerTransportProvider transportProvider,
          McpGatewayProperties properties) {
    return new ServletRegistrationBean<>(transportProvider, properties.mcpEndpoint());
  }
}
