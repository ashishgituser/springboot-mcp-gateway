package io.github.ashishgituser.mcpgateway.core.ratelimit;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;

/** What a rate limit bucket is keyed by. */
public enum RateLimitScope {
  /** One shared quota per caller, across every tool they call. */
  PRINCIPAL,
  /** One shared quota per tool, across every caller. */
  TOOL,
  /** A separate quota for each (caller, tool) pair. */
  PRINCIPAL_AND_TOOL;

  /**
   * The bucket key a call falls under. Shared by every {@link RateLimiter} implementation so an
   * in-memory and a distributed limiter partition traffic identically.
   */
  public String key(GatewayPrincipal principal, String namespacedToolName) {
    return switch (this) {
      case PRINCIPAL -> principal.name();
      case TOOL -> namespacedToolName;
      case PRINCIPAL_AND_TOOL -> principal.name() + "::" + namespacedToolName;
    };
  }
}
