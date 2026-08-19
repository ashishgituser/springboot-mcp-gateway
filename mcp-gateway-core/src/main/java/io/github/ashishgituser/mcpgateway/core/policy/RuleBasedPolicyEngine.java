package io.github.ashishgituser.mcpgateway.core.policy;

import java.util.List;

/**
 * Evaluates rules in the order they were configured: the first rule that matches decides, and calls
 * matching nothing fall back to the default effect. Ordering is what makes narrow exceptions
 * possible — a deny for {@code filesystem__delete*} placed above a broad allow for {@code
 * filesystem__*} wins.
 */
public class RuleBasedPolicyEngine implements PolicyEngine {

  private final List<PolicyRule> rules;
  private final PolicyEffect defaultEffect;

  public RuleBasedPolicyEngine(List<PolicyRule> rules, PolicyEffect defaultEffect) {
    this.rules = List.copyOf(rules);
    this.defaultEffect = defaultEffect;
  }

  @Override
  public PolicyDecision evaluate(GatewayPrincipal principal, String namespacedToolName) {
    for (int i = 0; i < rules.size(); i++) {
      PolicyRule rule = rules.get(i);
      if (rule.matches(principal, namespacedToolName)) {
        String reason = "rule %d (%s) matched".formatted(i, rule.effect().name().toLowerCase());
        return new PolicyDecision(rule.effect(), reason);
      }
    }
    return new PolicyDecision(defaultEffect, "no rule matched, applied default effect");
  }
}
