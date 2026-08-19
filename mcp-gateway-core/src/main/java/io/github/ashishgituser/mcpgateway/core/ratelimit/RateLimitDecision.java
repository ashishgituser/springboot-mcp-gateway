package io.github.ashishgituser.mcpgateway.core.ratelimit;

import java.time.Duration;

/** Whether a call is within quota, and if not, how long until it would be. */
public record RateLimitDecision(boolean allowed, Duration retryAfter, String reason) {

  public static RateLimitDecision allow() {
    return new RateLimitDecision(true, Duration.ZERO, null);
  }

  public static RateLimitDecision deny(Duration retryAfter, String reason) {
    return new RateLimitDecision(false, retryAfter, reason);
  }
}
