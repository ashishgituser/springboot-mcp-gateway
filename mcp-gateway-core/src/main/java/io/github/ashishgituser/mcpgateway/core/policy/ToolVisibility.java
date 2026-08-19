package io.github.ashishgituser.mcpgateway.core.policy;

/**
 * Decides which of the gateway's aggregated capabilities a caller is allowed to discover.
 *
 * <p>Refusing a call the caller can nonetheless see in the catalog still tells them the name,
 * description and argument schema of everything behind the gateway. Filtering discovery with the
 * same rules that gate execution closes that gap: a caller is never told about something it could
 * not have invoked.
 *
 * <p>The name passed in is whatever identifies the capability to policy — the namespaced name for a
 * tool or prompt, the URI for a resource.
 */
@FunctionalInterface
public interface ToolVisibility {

  boolean isVisible(GatewayPrincipal principal, String capabilityName);

  /** Shows the whole aggregated catalog to every caller. */
  static ToolVisibility unrestricted() {
    return (principal, capabilityName) -> true;
  }

  /** Hides anything the policy engine would refuse to invoke for this caller. */
  static ToolVisibility governedBy(PolicyEngine policyEngine) {
    return (principal, capabilityName) ->
        policyEngine.evaluate(principal, capabilityName).allowed();
  }
}
