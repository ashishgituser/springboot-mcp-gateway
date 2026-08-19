package io.github.ashishgituser.mcpgateway.autoconfigure;

import io.github.ashishgituser.mcpgateway.autoconfigure.McpGatewayProperties.Policy.Rule;
import io.github.ashishgituser.mcpgateway.autoconfigure.ratelimit.RateLimitStore;
import io.github.ashishgituser.mcpgateway.core.observability.ArgumentRedactor;
import io.github.ashishgituser.mcpgateway.core.observability.AuditLogger;
import io.github.ashishgituser.mcpgateway.core.observability.Slf4jAuditLogger;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyEngine;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyRule;
import io.github.ashishgituser.mcpgateway.core.policy.RuleBasedPolicyEngine;
import io.github.ashishgituser.mcpgateway.core.policy.ToolPattern;
import io.github.ashishgituser.mcpgateway.core.policy.ToolVisibility;
import io.github.ashishgituser.mcpgateway.core.protocol.GatewayInitRequestHandler;
import io.github.ashishgituser.mcpgateway.core.protocol.GatewayServerHandlers;
import io.github.ashishgituser.mcpgateway.core.ratelimit.Bucket4jRateLimiter;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimiter;
import io.github.ashishgituser.mcpgateway.core.routing.CatalogRefresher;
import io.github.ashishgituser.mcpgateway.core.routing.GatewayRouter;
import io.github.ashishgituser.mcpgateway.core.routing.PromptRegistry;
import io.github.ashishgituser.mcpgateway.core.routing.ResourceRegistry;
import io.github.ashishgituser.mcpgateway.core.routing.ToolRegistry;
import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamClientFactory;
import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServerDefinition;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.DefaultMcpStreamableServerSessionFactory;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import jakarta.servlet.Servlet;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import reactor.core.publisher.Mono;

@AutoConfiguration
@ConditionalOnClass(Servlet.class)
@ConditionalOnProperty(
    prefix = "mcp.gateway",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(McpGatewayProperties.class)
public class McpGatewayAutoConfiguration {

  private static final String SERVER_NAME = "mcp-gateway";

  @Bean
  public UpstreamClientFactory upstreamClientFactory(McpGatewayProperties properties) {
    McpGatewayProperties.CircuitBreaker breaker = properties.circuitBreaker();
    return new UpstreamClientFactory(breaker.failureThreshold(), breaker.openDuration());
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
  public PromptRegistry promptRegistry(List<UpstreamServer> upstreamServers) {
    PromptRegistry registry = new PromptRegistry(upstreamServers);
    registry.refresh();
    return registry;
  }

  @Bean
  public ResourceRegistry resourceRegistry(List<UpstreamServer> upstreamServers) {
    ResourceRegistry registry = new ResourceRegistry(upstreamServers);
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
   * Discovery is governed by the same engine as execution by default, so {@code tools/list} never
   * advertises a tool the caller would be denied on. Set {@code
   * mcp.gateway.policy.filter-tool-list=false} to publish the whole catalog and enforce only at
   * call time.
   */
  @Bean
  @ConditionalOnMissingBean(ToolVisibility.class)
  public ToolVisibility toolVisibility(McpGatewayProperties properties, PolicyEngine policyEngine) {
    return properties.policy().filterToolList()
        ? ToolVisibility.governedBy(policyEngine)
        : ToolVisibility.unrestricted();
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

  /**
   * Off only if explicitly disabled — unlike policy, logging every call changes nothing. Call
   * arguments are dropped before they are written unless {@code
   * mcp.gateway.audit.include-arguments} is on, in which case keys matching {@code
   * mcp.gateway.audit.redact} are masked first.
   */
  @Bean
  @ConditionalOnMissingBean(AuditLogger.class)
  public AuditLogger auditLogger(McpGatewayProperties properties) {
    McpGatewayProperties.Audit audit = properties.audit();
    if (!audit.enabled()) {
      return AuditLogger.noop();
    }
    Slf4jAuditLogger delegate = new Slf4jAuditLogger();
    return audit.includeArguments()
        ? AuditLogger.redacting(delegate, new ArgumentRedactor(audit.redact()))
        : AuditLogger.withoutArguments(delegate);
  }

  /**
   * Off by default, so a gateway with no rate-limit configuration forwards every call, as before
   * this feature existed. Once enabled, buckets are created lazily per key and kept in memory for
   * the process lifetime — which means quota is per replica. Set {@code
   * mcp.gateway.rate-limit.store=REDIS} to share it across replicas instead; that is wired by
   * {@link RedisRateLimiterAutoConfiguration}, which runs first and only when Spring Data Redis is
   * on the classpath.
   */
  @Bean
  @ConditionalOnMissingBean(RateLimiter.class)
  public RateLimiter rateLimiter(McpGatewayProperties properties) {
    McpGatewayProperties.RateLimit rateLimit = properties.rateLimit();
    if (!rateLimit.enabled()) {
      return RateLimiter.unlimited();
    }
    if (rateLimit.store() == RateLimitStore.REDIS) {
      // Reaching here means the Redis configuration backed off. Falling back to in-memory would
      // silently hand out one quota per replica, which is the opposite of what was asked for.
      throw new IllegalStateException(
          "mcp.gateway.rate-limit.store=REDIS requires spring-boot-starter-data-redis on the "
              + "classpath and a StringRedisTemplate bean (configure spring.data.redis.*)");
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
      PromptRegistry promptRegistry,
      ResourceRegistry resourceRegistry,
      PolicyEngine policyEngine,
      RateLimiter rateLimiter,
      ObservationRegistry observationRegistry,
      AuditLogger auditLogger,
      ToolVisibility toolVisibility) {
    return new GatewayRouter(
        toolRegistry,
        promptRegistry,
        resourceRegistry,
        policyEngine,
        rateLimiter,
        observationRegistry,
        auditLogger,
        toolVisibility);
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
  public GatewayServerHandlers gatewayServerHandlers(GatewayRouter gatewayRouter) {
    return new GatewayServerHandlers(gatewayRouter);
  }

  /**
   * Wires the gateway's own request handlers into the transport instead of building an {@code
   * McpServer} over a fixed tool list. The SDK server answers {@code tools/list} with the same list
   * for every caller, which a gateway cannot do: its catalog is assembled from several upstreams
   * and filtered per principal. Owning the session factory is what makes that possible.
   */
  @Bean
  public McpStreamableServerSession.Factory gatewaySessionFactory(
      HttpServletStreamableServerTransportProvider transportProvider,
      GatewayServerHandlers handlers,
      McpGatewayProperties properties) {
    McpSchema.ServerCapabilities capabilities =
        McpSchema.ServerCapabilities.builder()
            .tools(true)
            .prompts(true)
            .resources(false, true)
            .build();
    GatewayInitRequestHandler initRequestHandler =
        new GatewayInitRequestHandler(
            transportProvider.protocolVersions(),
            new McpSchema.Implementation(SERVER_NAME, gatewayVersion()),
            capabilities,
            null);
    DefaultMcpStreamableServerSessionFactory sessionFactory =
        new DefaultMcpStreamableServerSessionFactory(
            properties.requestTimeout(),
            initRequestHandler,
            handlers.requestHandlers(),
            handlers.notificationHandlers(),
            sessionId -> Mono.empty());
    transportProvider.setSessionFactory(sessionFactory);
    return sessionFactory;
  }

  /**
   * Keeps the published catalog in step with the upstreams: one that was down at boot joins on a
   * later pass, and one that gained or lost tools is picked up without a restart. Set {@code
   * mcp.gateway.refresh-interval=0} to freeze the catalog at whatever startup found.
   */
  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnMissingBean(CatalogRefresher.class)
  public CatalogRefresher catalogRefresher(
      ToolRegistry toolRegistry,
      PromptRegistry promptRegistry,
      ResourceRegistry resourceRegistry,
      McpGatewayProperties properties,
      HttpServletStreamableServerTransportProvider transportProvider) {
    return new CatalogRefresher(
        () -> {
          // Not short-circuited: every registry must refresh even if an earlier one already
          // reported a change.
          boolean toolsChanged = toolRegistry.refresh();
          boolean promptsChanged = promptRegistry.refresh();
          boolean resourcesChanged = resourceRegistry.refresh();
          return toolsChanged || promptsChanged || resourcesChanged;
        },
        properties.refreshInterval(),
        () ->
            transportProvider
                .notifyClients(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, null)
                .subscribe());
  }

  @Bean
  @DependsOn("gatewaySessionFactory")
  public ServletRegistrationBean<HttpServletStreamableServerTransportProvider>
      mcpServletRegistration(
          HttpServletStreamableServerTransportProvider transportProvider,
          McpGatewayProperties properties) {
    return new ServletRegistrationBean<>(transportProvider, properties.mcpEndpoint());
  }

  private static String gatewayVersion() {
    return Optional.ofNullable(
            McpGatewayAutoConfiguration.class.getPackage().getImplementationVersion())
        .orElse("unknown");
  }
}
