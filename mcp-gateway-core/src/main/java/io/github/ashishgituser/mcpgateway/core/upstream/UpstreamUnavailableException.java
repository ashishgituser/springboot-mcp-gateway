package io.github.ashishgituser.mcpgateway.core.upstream;

/** Thrown when an upstream cannot be reached, or when its circuit breaker is open. */
public class UpstreamUnavailableException extends RuntimeException {

  private final String upstreamServerId;

  public UpstreamUnavailableException(String upstreamServerId, String reason) {
    super("Upstream '%s' is unavailable: %s".formatted(upstreamServerId, reason));
    this.upstreamServerId = upstreamServerId;
  }

  public UpstreamUnavailableException(String upstreamServerId, String reason, Throwable cause) {
    super(
        "Upstream '%s' is unavailable: %s (%s)"
            .formatted(upstreamServerId, reason, cause.getMessage()),
        cause);
    this.upstreamServerId = upstreamServerId;
  }

  public String upstreamServerId() {
    return upstreamServerId;
  }
}
