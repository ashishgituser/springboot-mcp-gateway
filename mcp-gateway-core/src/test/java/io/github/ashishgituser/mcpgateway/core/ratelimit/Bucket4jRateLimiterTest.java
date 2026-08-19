package io.github.ashishgituser.mcpgateway.core.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class Bucket4jRateLimiterTest {

  private static final GatewayPrincipal ALICE = new GatewayPrincipal("alice");
  private static final GatewayPrincipal BOB = new GatewayPrincipal("bob");

  @Test
  void allowsCallsUpToCapacityThenDeniesTheNext() {
    RateLimiter limiter =
        new Bucket4jRateLimiter(2, 2, Duration.ofMinutes(1), RateLimitScope.PRINCIPAL);

    assertThat(limiter.checkLimit(ALICE, "fs__readFile").allowed()).isTrue();
    assertThat(limiter.checkLimit(ALICE, "fs__readFile").allowed()).isTrue();
    RateLimitDecision third = limiter.checkLimit(ALICE, "fs__readFile");

    assertThat(third.allowed()).isFalse();
    assertThat(third.retryAfter()).isGreaterThan(Duration.ZERO);
  }

  @Test
  void tracksSeparateQuotasPerPrincipalWhenScopedToPrincipal() {
    RateLimiter limiter =
        new Bucket4jRateLimiter(1, 1, Duration.ofMinutes(1), RateLimitScope.PRINCIPAL);

    assertThat(limiter.checkLimit(ALICE, "fs__readFile").allowed()).isTrue();
    assertThat(limiter.checkLimit(ALICE, "fs__readFile").allowed()).isFalse();
    assertThat(limiter.checkLimit(BOB, "fs__readFile").allowed()).isTrue();
  }

  @Test
  void sharesOneQuotaAcrossPrincipalsWhenScopedToTool() {
    RateLimiter limiter = new Bucket4jRateLimiter(1, 1, Duration.ofMinutes(1), RateLimitScope.TOOL);

    assertThat(limiter.checkLimit(ALICE, "fs__readFile").allowed()).isTrue();
    assertThat(limiter.checkLimit(BOB, "fs__readFile").allowed()).isFalse();
  }

  @Test
  void tracksSeparateQuotasPerPrincipalAndToolPairWhenScopedToBoth() {
    RateLimiter limiter =
        new Bucket4jRateLimiter(1, 1, Duration.ofMinutes(1), RateLimitScope.PRINCIPAL_AND_TOOL);

    assertThat(limiter.checkLimit(ALICE, "fs__readFile").allowed()).isTrue();
    assertThat(limiter.checkLimit(ALICE, "fs__writeFile").allowed()).isTrue();
    assertThat(limiter.checkLimit(ALICE, "fs__readFile").allowed()).isFalse();
  }
}
