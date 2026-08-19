package io.github.ashishgituser.mcpgateway.core.ratelimit;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;

/** Thrown when a caller has exceeded their quota for a tool call. */
public class RateLimitExceededException extends RuntimeException {

  private final transient GatewayPrincipal principal;
  private final String toolName;
  private final transient RateLimitDecision decision;

  public RateLimitExceededException(
      GatewayPrincipal principal, String toolName, RateLimitDecision decision) {
    super(
        "Rate limit exceeded for tool '%s' by principal '%s', retry after %s"
            .formatted(toolName, principal.name(), decision.retryAfter()));
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

  public RateLimitDecision decision() {
    return decision;
  }
}
