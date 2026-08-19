package io.github.ashishgituser.mcpgateway.autoconfigure;

import io.github.ashishgituser.mcpgateway.autoconfigure.McpGatewayProperties.Policy.Rule;
import io.github.ashishgituser.mcpgateway.core.observability.AuditLogger;
import io.github.ashishgituser.mcpgateway.core.observability.Slf4jAuditLogger;
import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyEngine;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyRule;
import io.github.ashishgituser.mcpgateway.core.policy.RuleBasedPolicyEngine;
import io.github.ashishgituser.mcpgateway.core.policy.ToolPattern;
import io.github.ashishgituser.mcpgateway.core.ratelimit.Bucket4jRateLimiter;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimiter;
import io.github.ashishgituser.mcpgateway.core.routing.GatewayRouter;
import io.github.ashishgituser.mcpgateway.core.routing.ToolRegistry;
import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamClientFactory;
import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServerDefinition;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.Servlet;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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

  /**
   * Off by default so a gateway with no policy configuration keeps forwarding every call, as before
   * this feature existed. Once {@code mcp.gateway.policy.enabled=true}, calls matching no rule fall
   * back to {@code policy.defaultEffect} (DENY unless overridden).
   */
  @Bean
  @ConditionalOnMissingBean(PolicyEngine.class)
  public PolicyEngine policyEngine(McpGatewayProperties properties) {
    McpGatewayProperties.Policy policy = properties.policy();
    if (!policy.enabled()) {
      return PolicyEngine.permitAll();
    }
    List<PolicyRule> rules =
        policy.rules().stream().map(McpGatewayAutoConfiguration::toPolicyRule).toList();
    return new RuleBasedPolicyEngine(rules, policy.defaultEffect());
  }

  private static PolicyRule toPolicyRule(Rule rule) {
    List<ToolPattern> tools = rule.tools().stream().map(ToolPattern::of).toList();
    return new PolicyRule(rule.effect(), rule.principals(), rule.roles(), tools);
  }

  /**
   * Backs off to a no-op registry when Micrometer's observation handlers aren't wired up (i.e.
   * Actuator isn't on the classpath), so tool calls are still observed for free the moment it is.
   */
  @Bean
  @ConditionalOnMissingBean(ObservationRegistry.class)
  public ObservationRegistry observationRegistry() {
    return ObservationRegistry.NOOP;
  }

  /** Off only if explicitly disabled — unlike policy, logging every call changes nothing. */
  @Bean
  @ConditionalOnMissingBean(AuditLogger.class)
  public AuditLogger auditLogger(McpGatewayProperties properties) {
    return properties.audit().enabled() ? new Slf4jAuditLogger() : AuditLogger.noop();
  }

  /**
   * Off by default, so a gateway with no rate-limit configuration forwards every call, as before
   * this feature existed. Once enabled, buckets are created lazily per key and kept in memory for
   * the process lifetime.
   */
  @Bean
  @ConditionalOnMissingBean(RateLimiter.class)
  public RateLimiter rateLimiter(McpGatewayProperties properties) {
    McpGatewayProperties.RateLimit rateLimit = properties.rateLimit();
    if (!rateLimit.enabled()) {
      return RateLimiter.unlimited();
    }
    return new Bucket4jRateLimiter(
        rateLimit.capacity(),
        rateLimit.refillTokens(),
        rateLimit.refillPeriod(),
        rateLimit.scope());
  }

  @Bean
  public GatewayRouter gatewayRouter(
      ToolRegistry toolRegistry,
      PolicyEngine policyEngine,
      RateLimiter rateLimiter,
      ObservationRegistry observationRegistry,
      AuditLogger auditLogger) {
    return new GatewayRouter(
        toolRegistry, policyEngine, rateLimiter, observationRegistry, auditLogger);
  }

  /**
   * Resolves the caller from Spring Security's context — populated by {@code mcp-server-security}
   * (or the application's own filter chain) before the MCP servlet runs — whenever Spring Security
   * is present. This is what makes role-based policy rules work without the gateway having any auth
   * logic of its own.
   */
  @Bean
  @ConditionalOnClass(name = "org.springframework.security.core.Authentication")
  @ConditionalOnMissingBean(PrincipalResolver.class)
  public PrincipalResolver securityContextPrincipalResolver() {
    return new SecurityContextPrincipalResolver();
  }

  /** Fallback for applications that haven't added Spring Security: uses the servlet principal. */
  @Bean
  @ConditionalOnMissingBean(PrincipalResolver.class)
  public PrincipalResolver servletPrincipalResolver() {
    return new ServletPrincipalResolver();
  }

  @Bean
  public PrincipalContextExtractor principalContextExtractor(PrincipalResolver principalResolver) {
    return new PrincipalContextExtractor(principalResolver);
  }

  @Bean
  public HttpServletStreamableServerTransportProvider mcpTransportProvider(
      McpGatewayProperties properties, PrincipalContextExtractor principalContextExtractor) {
    return HttpServletStreamableServerTransportProvider.builder()
        .mcpEndpoint(properties.mcpEndpoint())
        .contextExtractor(principalContextExtractor)
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
                        .callHandler(
                            (exchange, request) -> {
                              Object contextPrincipal =
                                  exchange.transportContext().get(GatewayPrincipal.CONTEXT_KEY);
                              GatewayPrincipal principal =
                                  contextPrincipal instanceof GatewayPrincipal resolved
                                      ? resolved
                                      : GatewayPrincipal.ANONYMOUS;
                              return gatewayRouter.callTool(request, principal);
                            })
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
