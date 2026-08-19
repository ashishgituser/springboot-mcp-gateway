package io.github.ashishgituser.mcpgateway.core.upstream;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stops the gateway from spending a request timeout on an upstream that is known to be down.
 *
 * <p>After {@code failureThreshold} consecutive failures the breaker opens and every call fails
 * immediately for {@code openDuration}. The first call after that window is let through as a probe:
 * if it succeeds the breaker closes, otherwise the window starts again. Deliberately small — a
 * gateway fronting a handful of upstreams does not need bulkheads or sliding windows, and core
 * stays free of a resilience library.
 */
public final class CircuitBreaker {

  /** Consecutive failures tolerated before an upstream is considered down. */
  public static final int DEFAULT_FAILURE_THRESHOLD = 3;

  /** How long the breaker stays open before letting a probe through. */
  public static final Duration DEFAULT_OPEN_DURATION = Duration.ofSeconds(30);

  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final int failureThreshold;
  private final long openDurationNanos;
  private final AtomicInteger consecutiveFailures = new AtomicInteger();

  private volatile State state = State.CLOSED;
  private volatile long openedAtNanos;

  public CircuitBreaker() {
    this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_OPEN_DURATION);
  }

  public CircuitBreaker(int failureThreshold, Duration openDuration) {
    if (failureThreshold < 1) {
      throw new IllegalArgumentException("Failure threshold must be at least 1");
    }
    this.failureThreshold = failureThreshold;
    this.openDurationNanos = openDuration.toNanos();
  }

  /** Whether a call may be attempted now. Moves an expired OPEN breaker to HALF_OPEN. */
  public boolean allowsRequest() {
    if (state == State.OPEN) {
      if (System.nanoTime() - openedAtNanos < openDurationNanos) {
        return false;
      }
      state = State.HALF_OPEN;
    }
    return true;
  }

  public void recordSuccess() {
    consecutiveFailures.set(0);
    state = State.CLOSED;
  }

  public void recordFailure() {
    if (state == State.HALF_OPEN || consecutiveFailures.incrementAndGet() >= failureThreshold) {
      openedAtNanos = System.nanoTime();
      state = State.OPEN;
    }
  }

  public State state() {
    return state;
  }
}
