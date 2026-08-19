package io.github.ashishgituser.mcpgateway.autoconfigure;

import io.github.ashishgituser.mcpgateway.core.policy.PolicyEffect;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "mcp.gateway")
public record McpGatewayProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("/mcp") String mcpEndpoint,
    List<Server> servers,
    Policy policy,
    Audit audit) {

  public McpGatewayProperties {
    if (servers == null) {
      servers = List.of();
    }
    if (policy == null) {
      policy = new Policy(false, PolicyEffect.DENY, List.of());
    }
    if (audit == null) {
      audit = new Audit(true);
    }
  }

  public record Server(String id, String endpoint, @DefaultValue("10s") Duration requestTimeout) {}

  /**
   * Policy is opt-in: {@code enabled: false} (the default) preserves the pre-Phase-3 behaviour
   * where every authenticated call is forwarded. Once enabled, {@code defaultEffect} governs calls
   * that no rule matches — it defaults to {@code DENY} so a gateway with rules enabled but not yet
   * written blocks everything instead of silently allowing it.
   */
  public record Policy(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("DENY") PolicyEffect defaultEffect,
      List<Rule> rules) {

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
  public record Audit(@DefaultValue("true") boolean enabled) {}
}
