package io.github.ashishgituser.mcpgateway.core.policy;

/** Decides whether a caller may invoke a namespaced tool. */
@FunctionalInterface
public interface PolicyEngine {

  PolicyDecision evaluate(GatewayPrincipal principal, String namespacedToolName);

  /** Lets every call through — the engine used when policy enforcement is switched off. */
  static PolicyEngine permitAll() {
    return (principal, namespacedToolName) ->
        PolicyDecision.allow("policy enforcement is disabled");
  }
}
