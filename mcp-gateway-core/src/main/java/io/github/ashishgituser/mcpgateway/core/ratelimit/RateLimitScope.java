package io.github.ashishgituser.mcpgateway.core.ratelimit;

/** What a rate limit bucket is keyed by. */
public enum RateLimitScope {
  /** One shared quota per caller, across every tool they call. */
  PRINCIPAL,
  /** One shared quota per tool, across every caller. */
  TOOL,
  /** A separate quota for each (caller, tool) pair. */
  PRINCIPAL_AND_TOOL
}
