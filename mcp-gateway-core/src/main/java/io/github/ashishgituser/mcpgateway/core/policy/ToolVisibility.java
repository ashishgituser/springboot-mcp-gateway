package io.github.ashishgituser.mcpgateway.core.policy;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;

/**
 * Decides which of the gateway's aggregated tools a caller is allowed to discover.
 *
 * <p>Refusing a call the caller can nonetheless see in the catalog still tells them the name,
 * description and argument schema of every tool behind the gateway. Filtering discovery with the
 * same rules that gate execution closes that gap: an agent is never told about a tool it could not
 * have invoked.
 */
@FunctionalInterface
public interface ToolVisibility {

  List<Tool> visibleTo(GatewayPrincipal principal, List<Tool> tools);

  /** Shows the whole aggregated catalog to every caller. */
  static ToolVisibility unrestricted() {
    return (principal, tools) -> tools;
  }

  /** Hides any tool the policy engine would refuse to execute for this caller. */
  static ToolVisibility governedBy(PolicyEngine policyEngine) {
    return (principal, tools) ->
        tools.stream()
            .filter(tool -> policyEngine.evaluate(principal, tool.name()).allowed())
            .toList();
  }
}
