package io.github.ashishgituser.mcpgateway.autoconfigure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimitDecision;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimitScope;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * Runs against a real Redis, because the part worth testing is a Lua script and Redis is the only
 * thing that can tell us whether it is right.
 */
@EnabledIf("redisReachable")
class RedisRateLimiterTest {

  /**
   * Point at an already-running Redis with -Dmcp.test.redis=host:port; otherwise Testcontainers
   * starts one, and the whole class is skipped on a machine that has neither.
   */
  private static final String EXTERNAL_REDIS = System.getProperty("mcp.test.redis");

  static boolean redisReachable() {
    return EXTERNAL_REDIS != null || DockerClientFactory.instance().isDockerAvailable();
  }

  @SuppressWarnings("resource")
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private static StringRedisTemplate redis;

  @BeforeAll
  static void startRedis() {
    String host;
    int port;
    if (EXTERNAL_REDIS != null) {
      String[] parts = EXTERNAL_REDIS.split(":", 2);
      host = parts[0];
      port = Integer.parseInt(parts[1]);
    } else {
      REDIS.start();
      host = REDIS.getHost();
      port = REDIS.getMappedPort(6379);
    }
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
    factory.afterPropertiesSet();
    redis = new StringRedisTemplate(factory);
    redis.afterPropertiesSet();
  }

  @AfterAll
  static void stopRedis() {
    if (REDIS.isRunning()) {
      REDIS.stop();
    }
  }

  @Test
  void allowsUpToCapacityThenRefuses() {
    RedisRateLimiter limiter = limiter(3, 3, Duration.ofMinutes(5));
    GatewayPrincipal caller = uniqueCaller();

    assertThat(limiter.checkLimit(caller, "db__query").allowed()).isTrue();
    assertThat(limiter.checkLimit(caller, "db__query").allowed()).isTrue();
    assertThat(limiter.checkLimit(caller, "db__query").allowed()).isTrue();

    RateLimitDecision refused = limiter.checkLimit(caller, "db__query");
    assertThat(refused.allowed()).isFalse();
    assertThat(refused.reason()).contains("rate limit exceeded");
    assertThat(refused.retryAfter()).isPositive();
  }

  @Test
  void twoGatewayInstancesShareOneQuota() {
    // The whole point of the Redis store: two replicas must not each get their own allowance.
    RedisRateLimiter replicaOne = limiter(2, 2, Duration.ofMinutes(5));
    RedisRateLimiter replicaTwo = limiter(2, 2, Duration.ofMinutes(5));
    GatewayPrincipal caller = uniqueCaller();

    assertThat(replicaOne.checkLimit(caller, "db__query").allowed()).isTrue();
    assertThat(replicaTwo.checkLimit(caller, "db__query").allowed()).isTrue();

    assertThat(replicaOne.checkLimit(caller, "db__query").allowed()).isFalse();
    assertThat(replicaTwo.checkLimit(caller, "db__query").allowed()).isFalse();
  }

  @Test
  void refillsOverTime() throws InterruptedException {
    RedisRateLimiter limiter = limiter(1, 1, Duration.ofMillis(300));
    GatewayPrincipal caller = uniqueCaller();

    assertThat(limiter.checkLimit(caller, "db__query").allowed()).isTrue();
    assertThat(limiter.checkLimit(caller, "db__query").allowed()).isFalse();

    Thread.sleep(400);

    assertThat(limiter.checkLimit(caller, "db__query").allowed()).isTrue();
  }

  @Test
  void keepsScopesApart() {
    RedisRateLimiter limiter = limiter(1, 1, Duration.ofMinutes(5));
    GatewayPrincipal alice = uniqueCaller();
    GatewayPrincipal bob = uniqueCaller();

    assertThat(limiter.checkLimit(alice, "db__query").allowed()).isTrue();
    assertThat(limiter.checkLimit(alice, "db__query").allowed()).isFalse();

    // Bob's quota is untouched by Alice exhausting hers.
    assertThat(limiter.checkLimit(bob, "db__query").allowed()).isTrue();
  }

  private static RedisRateLimiter limiter(long capacity, long refillTokens, Duration period) {
    return new RedisRateLimiter(
        redis, capacity, refillTokens, period, RateLimitScope.PRINCIPAL_AND_TOOL);
  }

  /** Buckets outlive a test method, so every test gets a principal nothing else has spent. */
  private static GatewayPrincipal uniqueCaller() {
    return new GatewayPrincipal(UUID.randomUUID().toString());
  }
}
