package io.github.ashishgituser.mcpgateway.core.ratelimit;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default {@link RateLimiter}: one in-memory token bucket per key, refilled at a fixed rate.
 * Buckets are created lazily and kept for the process lifetime, so cardinality is bounded by
 * however many distinct keys the configured {@link RateLimitScope} produces.
 */
public class Bucket4jRateLimiter implements RateLimiter {

  private final long capacity;
  private final long refillTokens;
  private final Duration refillPeriod;
  private final RateLimitScope scope;
  private final ConcurrentMap<String, Bucket> bucketsByKey = new ConcurrentHashMap<>();

  public Bucket4jRateLimiter(
      long capacity, long refillTokens, Duration refillPeriod, RateLimitScope scope) {
    this.capacity = capacity;
    this.refillTokens = refillTokens;
    this.refillPeriod = refillPeriod;
    this.scope = scope;
  }

  @Override
  public RateLimitDecision checkLimit(GatewayPrincipal principal, String namespacedToolName) {
    String key = scope.key(principal, namespacedToolName);
    Bucket bucket = bucketsByKey.computeIfAbsent(key, k -> newBucket());
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      return RateLimitDecision.allow();
    }
    Duration retryAfter = Duration.ofNanos(probe.getNanosToWaitForRefill());
    return RateLimitDecision.deny(retryAfter, "rate limit exceeded for key '%s'".formatted(key));
  }

  private Bucket newBucket() {
    Bandwidth limit =
        Bandwidth.builder().capacity(capacity).refillGreedy(refillTokens, refillPeriod).build();
    return Bucket.builder().addLimit(limit).build();
  }
}
