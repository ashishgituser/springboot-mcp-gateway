package io.github.ashishgituser.mcpgateway.core.policy;

import java.util.List;
import java.util.Set;

/**
 * One allow/deny rule. A rule matches a call when the caller matches every subject constraint that
 * is set and the tool matches one of the patterns. An empty constraint means "any": a rule with no
 * principals, no roles and no tool patterns matches every call.
 */
public record PolicyRule(
    PolicyEffect effect, Set<String> principals, Set<String> roles, List<ToolPattern> tools) {

  public PolicyRule {
    if (effect == null) {
      throw new IllegalArgumentException("Policy rule needs an effect (allow or deny)");
    }
    principals = principals == null ? Set.of() : Set.copyOf(principals);
    roles = roles == null ? Set.of() : Set.copyOf(roles);
    tools = tools == null ? List.of() : List.copyOf(tools);
  }

  public boolean matches(GatewayPrincipal principal, String namespacedToolName) {
    return matchesPrincipal(principal) && matchesRole(principal) && matchesTool(namespacedToolName);
  }

  private boolean matchesPrincipal(GatewayPrincipal principal) {
    return principals.isEmpty() || principals.contains(principal.name());
  }

  private boolean matchesRole(GatewayPrincipal principal) {
    return roles.isEmpty() || principal.hasAnyRole(roles);
  }

  private boolean matchesTool(String namespacedToolName) {
    return tools.isEmpty()
        || tools.stream().anyMatch(pattern -> pattern.matches(namespacedToolName));
  }
}
