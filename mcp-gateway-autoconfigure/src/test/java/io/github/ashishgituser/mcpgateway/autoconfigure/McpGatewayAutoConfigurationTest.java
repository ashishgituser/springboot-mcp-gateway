package io.github.ashishgituser.mcpgateway.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ashishgituser.mcpgateway.core.observability.ArgumentRedactor;
import io.github.ashishgituser.mcpgateway.core.observability.AuditEvent;
import io.github.ashishgituser.mcpgateway.core.observability.AuditLogger;
import io.github.ashishgituser.mcpgateway.core.observability.AuditOutcome;
import io.github.ashishgituser.mcpgateway.core.observability.Slf4jAuditLogger;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyEngine;
import io.github.ashishgituser.mcpgateway.core.policy.ToolVisibility;
import io.github.ashishgituser.mcpgateway.core.protocol.GatewayServerHandlers;
import io.github.ashishgituser.mcpgateway.core.ratelimit.Bucket4jRateLimiter;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimiter;
import io.github.ashishgituser.mcpgateway.core.routing.GatewayRouter;
import io.github.ashishgituser.mcpgateway.core.routing.ToolRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

class McpGatewayAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  McpGatewayAutoConfiguration.class, UpstreamHealthAutoConfiguration.class));

  @Test
  void wiresAllGatewayBeansWithNoServersConfigured() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(ToolRegistry.class);
          assertThat(context).hasSingleBean(GatewayRouter.class);
          assertThat(context).hasSingleBean(PolicyEngine.class);
          assertThat(context).hasSingleBean(PrincipalResolver.class);
          assertThat(context).hasSingleBean(PrincipalContextExtractor.class);
          assertThat(context).hasSingleBean(ToolVisibility.class);
          assertThat(context).hasSingleBean(GatewayServerHandlers.class);
          assertThat(context).hasSingleBean(McpStreamableServerSession.Factory.class);
          assertThat(context).hasSingleBean(HttpServletStreamableServerTransportProvider.class);
          assertThat(context).hasSingleBean(ServletRegistrationBean.class);
          assertThat(context).hasSingleBean(ObservationRegistry.class);
          assertThat(context).hasSingleBean(AuditLogger.class);
          assertThat(context).hasSingleBean(HealthIndicator.class);
          assertThat(context).hasSingleBean(RateLimiter.class);
        });
  }

  @Test
  void auditLoggerDropsCallArgumentsByDefault() {
    contextRunner.run(
        context -> {
          List<AuditEvent> recorded = new ArrayList<>();
          AuditLogger.withoutArguments(recorded::add)
              .record(eventWith(Map.of("password", "hunter2")));

          assertThat(recorded).singleElement().extracting(AuditEvent::arguments).isNull();
          assertThat(context.getBean(AuditLogger.class)).isNotNull();
        });
  }

  @Test
  void auditLoggerMasksSensitiveArgumentsWhenCaptureIsEnabled() {
    contextRunner
        .withPropertyValues("mcp.gateway.audit.include-arguments=true")
        .run(
            context -> {
              List<AuditEvent> recorded = new ArrayList<>();
              AuditLogger.redacting(recorded::add, ArgumentRedactor.withDefaults())
                  .record(eventWith(Map.of("query", "select 1", "apiKey", "sk-live-1")));

              assertThat(recorded)
                  .singleElement()
                  .extracting(AuditEvent::arguments)
                  .isEqualTo(Map.of("query", "select 1", "apiKey", "***"));
            });
  }

  private static AuditEvent eventWith(Map<String, Object> arguments) {
    return new AuditEvent(
        Instant.EPOCH,
        "alice",
        "database__query",
        AuditOutcome.ALLOWED,
        Duration.ZERO,
        null,
        arguments);
  }

  @Test
  void auditLoggerIsNoopWhenDisabled() {
    contextRunner
        .withPropertyValues("mcp.gateway.audit.enabled=false")
        .run(
            context ->
                assertThat(context.getBean(AuditLogger.class))
                    .isNotInstanceOf(Slf4jAuditLogger.class));
  }

  @Test
  void registersTheUpstreamsHealthIndicator() {
    contextRunner.run(
        context ->
            assertThat(context.getBean("upstreamsHealthIndicator"))
                .isInstanceOf(UpstreamHealthIndicator.class));
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
  void rateLimiterIsUnlimitedWhenNotEnabled() {
    contextRunner.run(
        context -> {
          RateLimiter rateLimiter = context.getBean(RateLimiter.class);
          var caller = io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal.ANONYMOUS;
          for (int i = 0; i < 1000; i++) {
            assertThat(rateLimiter.checkLimit(caller, "fs__anything").allowed()).isTrue();
          }
        });
  }

  @Test
  void rateLimiterEnforcesConfiguredCapacityWhenEnabled() {
    contextRunner
        .withPropertyValues(
            "mcp.gateway.rate-limit.enabled=true",
            "mcp.gateway.rate-limit.capacity=1",
            "mcp.gateway.rate-limit.refill-tokens=1",
            "mcp.gateway.rate-limit.refill-period=1m")
        .run(
            context -> {
              assertThat(context.getBean(RateLimiter.class))
                  .isInstanceOf(Bucket4jRateLimiter.class);
              RateLimiter rateLimiter = context.getBean(RateLimiter.class);
              var caller =
                  io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal.ANONYMOUS;

              assertThat(rateLimiter.checkLimit(caller, "fs__anything").allowed()).isTrue();
              assertThat(rateLimiter.checkLimit(caller, "fs__anything").allowed()).isFalse();
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
