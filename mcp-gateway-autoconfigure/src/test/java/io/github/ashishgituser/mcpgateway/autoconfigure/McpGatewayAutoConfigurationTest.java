package io.github.ashishgituser.mcpgateway.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ashishgituser.mcpgateway.core.routing.GatewayRouter;
import io.github.ashishgituser.mcpgateway.core.routing.ToolRegistry;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

class McpGatewayAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(McpGatewayAutoConfiguration.class));

  @Test
  void wiresAllGatewayBeansWithNoServersConfigured() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(ToolRegistry.class);
          assertThat(context).hasSingleBean(GatewayRouter.class);
          assertThat(context).hasSingleBean(McpSyncServer.class);
          assertThat(context).hasSingleBean(HttpServletStreamableServerTransportProvider.class);
          assertThat(context).hasSingleBean(ServletRegistrationBean.class);
        });
  }

  @Test
  void mapsTheServletToTheConfiguredEndpoint() {
    contextRunner
        .withPropertyValues("mcp.gateway.mcp-endpoint=/custom-mcp")
        .run(
            context -> {
              ServletRegistrationBean<?> registration =
                  context.getBean(ServletRegistrationBean.class);
              assertThat(registration.getUrlMappings()).containsExactly("/custom-mcp");
            });
  }

  @Test
  void backsOffWhenDisabled() {
    contextRunner
        .withPropertyValues("mcp.gateway.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(GatewayRouter.class));
  }
}
