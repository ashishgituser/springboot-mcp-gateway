package io.github.ashishgituser.mcpgateway.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ashishgituser.mcpgateway.core.policy.PolicyEngine;
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
          assertThat(context).hasSingleBean(PolicyEngine.class);
          assertThat(context).hasSingleBean(PrincipalResolver.class);
          assertThat(context).hasSingleBean(PrincipalContextExtractor.class);
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

  @Test
  void policyEngineIsPermitAllWhenPolicyIsNotEnabled() {
    contextRunner.run(
        context -> {
          PolicyEngine policyEngine = context.getBean(PolicyEngine.class);
          assertThat(
                  policyEngine
                      .evaluate(
                          io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal.ANONYMOUS,
                          "fs__anything")
                      .allowed())
              .isTrue();
        });
  }

  @Test
  void policyEngineEnforcesConfiguredRulesWhenEnabled() {
    contextRunner
        .withPropertyValues(
            "mcp.gateway.policy.enabled=true",
            "mcp.gateway.policy.default-effect=DENY",
            "mcp.gateway.policy.rules[0].effect=ALLOW",
            "mcp.gateway.policy.rules[0].roles[0]=admin",
            "mcp.gateway.policy.rules[0].tools[0]=fs__*")
        .run(
            context -> {
              PolicyEngine policyEngine = context.getBean(PolicyEngine.class);
              var admin =
                  new io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal(
                      "alice", java.util.Set.of("admin"));
              var viewer =
                  new io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal(
                      "bob", java.util.Set.of("viewer"));

              assertThat(policyEngine.evaluate(admin, "fs__readFile").allowed()).isTrue();
              assertThat(policyEngine.evaluate(viewer, "fs__readFile").allowed()).isFalse();
            });
  }

  @Test
  void fallsBackToTheServletPrincipalResolverWhenSpringSecurityIsAbsent() {
    contextRunner
        .withClassLoader(
            new org.springframework.boot.test.context.FilteredClassLoader(
                org.springframework.security.core.Authentication.class))
        .run(
            context ->
                assertThat(context.getBean(PrincipalResolver.class))
                    .isInstanceOf(ServletPrincipalResolver.class));
  }

  @Test
  void usesTheSecurityContextPrincipalResolverWhenSpringSecurityIsPresent() {
    contextRunner.run(
        context ->
            assertThat(context.getBean(PrincipalResolver.class))
                .isInstanceOf(SecurityContextPrincipalResolver.class));
  }
}
