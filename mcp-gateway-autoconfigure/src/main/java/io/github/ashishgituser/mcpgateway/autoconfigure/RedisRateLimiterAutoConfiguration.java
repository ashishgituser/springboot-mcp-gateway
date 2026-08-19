package io.github.ashishgituser.mcpgateway.autoconfigure;

import io.github.ashishgituser.mcpgateway.autoconfigure.ratelimit.RedisRateLimiter;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimiter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Shares rate limit buckets across gateway replicas via Redis.
 *
 * <p>Separate from {@link McpGatewayAutoConfiguration} because Spring Data Redis is an optional
 * dependency: a {@code @Bean} method mentioning {@code StringRedisTemplate} in its signature would
 * break the whole configuration class for everyone who does not have it on the classpath. Runs
 * before the main configuration so its {@link RateLimiter} wins the {@code
 * ConditionalOnMissingBean}.
 */
@AutoConfiguration(before = McpGatewayAutoConfiguration.class)
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "mcp.gateway.rate-limit", name = "store", havingValue = "REDIS")
@EnableConfigurationProperties(McpGatewayProperties.class)
public class RedisRateLimiterAutoConfiguration {

  @Bean
  @ConditionalOnBean(StringRedisTemplate.class)
  @ConditionalOnMissingBean(RateLimiter.class)
  public RateLimiter redisRateLimiter(
      McpGatewayProperties properties, StringRedisTemplate redisTemplate) {
    McpGatewayProperties.RateLimit rateLimit = properties.rateLimit();
    if (!rateLimit.enabled()) {
      return RateLimiter.unlimited();
    }
    return new RedisRateLimiter(
        redisTemplate,
        rateLimit.capacity(),
        rateLimit.refillTokens(),
        rateLimit.refillPeriod(),
        rateLimit.scope());
  }
}
