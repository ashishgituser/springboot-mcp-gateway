package io.github.ashishgituser.mcpgateway.core.ratelimit;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;

/** Decides whether a tool call is within quota. Implementations must be thread-safe. */
@FunctionalInterface
public interface RateLimiter {

  RateLimitDecision checkLimit(GatewayPrincipal principal, String namespacedToolName);

  static RateLimiter unlimited() {
    return (principal, toolName) -> RateLimitDecision.allow();
  }
}
