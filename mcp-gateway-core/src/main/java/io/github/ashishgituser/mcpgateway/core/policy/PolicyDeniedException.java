package io.github.ashishgituser.mcpgateway.core.policy;

/** Thrown when policy blocks a tool call before it reaches an upstream server. */
public class PolicyDeniedException extends RuntimeException {

  private final transient GatewayPrincipal principal;
  private final String toolName;
  private final transient PolicyDecision decision;

  public PolicyDeniedException(
      GatewayPrincipal principal, String toolName, PolicyDecision decision) {
    super(
        "Access to tool '%s' denied for principal '%s': %s"
            .formatted(toolName, principal.name(), decision.reason()));
    this.principal = principal;
    this.toolName = toolName;
    this.decision = decision;
  }

  public GatewayPrincipal principal() {
    return principal;
  }

  public String toolName() {
    return toolName;
  }

  public PolicyDecision decision() {
    return decision;
  }
}
