package io.github.ashishgituser.mcpgateway.autoconfigure.ratelimit;

/** Where rate limit buckets live. */
public enum RateLimitStore {
  /** In the gateway process. Simple, but quota multiplies by the number of replicas. */
  MEMORY,
  /** In Redis, shared by every replica. Needs spring-boot-starter-data-redis on the classpath. */
  REDIS
}
