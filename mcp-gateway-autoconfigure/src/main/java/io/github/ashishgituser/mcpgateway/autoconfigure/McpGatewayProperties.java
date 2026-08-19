package io.github.ashishgituser.mcpgateway.autoconfigure;

import io.github.ashishgituser.mcpgateway.core.observability.ArgumentRedactor;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyEffect;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimitScope;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "mcp.gateway")
public record McpGatewayProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("/mcp") String mcpEndpoint,
    @DefaultValue("20s") Duration requestTimeout,
    @DefaultValue("60s") Duration refreshInterval,
    List<Server> servers,
    Policy policy,
    Audit audit,
    RateLimit rateLimit) {

  public McpGatewayProperties {
    if (servers == null) {
      servers = List.of();
    }
    if (policy == null) {
      policy = new Policy(false, PolicyEffect.DENY, List.of(), true);
    }
    if (audit == null) {
      audit = new Audit(true, false, List.of());
    }
    if (rateLimit == null) {
      rateLimit = new RateLimit(false, 100, 100, Duration.ofMinutes(1), RateLimitScope.PRINCIPAL);
    }
  }

  public record Server(String id, String endpoint, @DefaultValue("10s") Duration requestTimeout) {}

  /**
   * Policy is opt-in: {@code enabled: false} (the default) preserves the pre-Phase-3 behaviour
   * where every authenticated call is forwarded. Once enabled, {@code defaultEffect} governs calls
   * that no rule matches — it defaults to {@code DENY} so a gateway with rules enabled but not yet
   * written blocks everything instead of silently allowing it.
   *
   * <p>{@code filterToolList} additionally hides tools the caller could not invoke from {@code
   * tools/list}, so discovery never advertises what execution would refuse. Turn it off to keep the
   * full catalog visible to everyone and enforce only at call time.
   */
  public record Policy(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("DENY") PolicyEffect defaultEffect,
      List<Rule> rules,
      @DefaultValue("true") boolean filterToolList) {

    public Policy {
      if (rules == null) {
        rules = List.of();
      }
    }

    /**
     * One allow/deny rule. {@code principals}, {@code roles} and {@code tools} are all optional; an
     * omitted constraint matches anything. {@code tools} entries are globs over the namespaced tool
     * name, e.g. {@code database__*}.
     */
    public record Rule(
        PolicyEffect effect, Set<String> principals, Set<String> roles, List<String> tools) {

      public Rule {
        principals = principals == null ? Set.of() : principals;
        roles = roles == null ? Set.of() : roles;
        tools = tools == null ? List.of() : tools;
      }
    }
  }

  /**
   * Structured audit logging of every tool call decision. On by default since it only writes a log
   * line (via {@code Slf4jAuditLogger}) rather than changing behaviour — disable it if the volume
   * isn't wanted.
   */
  public record Audit(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("false") boolean includeArguments,
      List<String> redact) {

    public Audit {
      if (redact == null || redact.isEmpty()) {
        redact = ArgumentRedactor.DEFAULT_PATTERNS;
      }
    }
  }

  /**
   * Off by default. Once enabled, each call consumes one token from a bucket keyed by {@code
   * scope}; the bucket refills by {@code refillTokens} every {@code refillPeriod}, up to {@code
   * capacity}.
   */
  public record RateLimit(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("100") long capacity,
      @DefaultValue("100") long refillTokens,
      @DefaultValue("1m") Duration refillPeriod,
      @DefaultValue("PRINCIPAL") RateLimitScope scope) {}
}
