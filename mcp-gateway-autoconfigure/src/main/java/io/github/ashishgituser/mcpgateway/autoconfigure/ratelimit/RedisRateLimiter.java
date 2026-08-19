package io.github.ashishgituser.mcpgateway.autoconfigure.ratelimit;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimitDecision;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimitScope;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimiter;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * A token bucket held in Redis, so quota is shared by every gateway replica instead of being
 * multiplied by however many are running.
 *
 * <p>The refill-and-consume is a Lua script, which Redis runs atomically — without that, two
 * replicas checking the same bucket at the same moment can both see a token and both spend it.
 * Elapsed time comes from Redis's own clock rather than the caller's, so replicas with skewed
 * clocks still agree on how much a bucket has refilled.
 */
public class RedisRateLimiter implements RateLimiter {

  private static final String KEY_PREFIX = "mcp:gateway:ratelimit:";

  private static final String TOKEN_BUCKET_SCRIPT =
      """
      local key = KEYS[1]
      local capacity = tonumber(ARGV[1])
      local refillTokens = tonumber(ARGV[2])
      local refillPeriodMs = tonumber(ARGV[3])

      local time = redis.call('TIME')
      local nowMs = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)

      local state = redis.call('HMGET', key, 'tokens', 'updatedAt')
      local tokens = tonumber(state[1])
      local updatedAt = tonumber(state[2])
      if tokens == nil or updatedAt == nil then
        tokens = capacity
        updatedAt = nowMs
      end

      local elapsed = nowMs - updatedAt
      if elapsed > 0 then
        local refill = math.floor(elapsed * refillTokens / refillPeriodMs)
        if refill > 0 then
          tokens = math.min(capacity, tokens + refill)
          -- Advance by whole tokens only, so the remainder is not silently discarded.
          updatedAt = updatedAt + math.floor(refill * refillPeriodMs / refillTokens)
        end
      end

      local allowed = 0
      local waitMs = 0
      if tokens >= 1 then
        tokens = tokens - 1
        allowed = 1
      else
        waitMs = math.ceil(refillPeriodMs / refillTokens) - (nowMs - updatedAt)
        if waitMs < 0 then waitMs = 0 end
      end

      redis.call('HSET', key, 'tokens', tokens, 'updatedAt', updatedAt)
      -- A bucket that has sat untouched long enough to be full again carries no information.
      redis.call('PEXPIRE', key, math.ceil(capacity * refillPeriodMs / refillTokens) + refillPeriodMs)
      return {allowed, waitMs}
      """;

  private final StringRedisTemplate redis;
  private final RedisScript<List> script;
  private final long capacity;
  private final long refillTokens;
  private final Duration refillPeriod;
  private final RateLimitScope scope;

  public RedisRateLimiter(
      StringRedisTemplate redis,
      long capacity,
      long refillTokens,
      Duration refillPeriod,
      RateLimitScope scope) {
    this.redis = redis;
    this.script = new DefaultRedisScript<>(TOKEN_BUCKET_SCRIPT, List.class);
    this.capacity = capacity;
    this.refillTokens = refillTokens;
    this.refillPeriod = refillPeriod;
    this.scope = scope;
  }

  @Override
  @SuppressWarnings("unchecked")
  public RateLimitDecision checkLimit(GatewayPrincipal principal, String namespacedToolName) {
    String key = scope.key(principal, namespacedToolName);
    List<Long> result =
        redis.execute(
            script,
            List.of(KEY_PREFIX + key),
            Long.toString(capacity),
            Long.toString(refillTokens),
            Long.toString(refillPeriod.toMillis()));

    if (result == null || result.size() < 2) {
      // Never fail a call because the limiter could not answer - a broken limiter should not take
      // the gateway down with it. Policy has already run by this point.
      return RateLimitDecision.allow();
    }
    if (result.get(0) == 1L) {
      return RateLimitDecision.allow();
    }
    return RateLimitDecision.deny(
        Duration.ofMillis(result.get(1)), "rate limit exceeded for key '%s'".formatted(key));
  }
}
