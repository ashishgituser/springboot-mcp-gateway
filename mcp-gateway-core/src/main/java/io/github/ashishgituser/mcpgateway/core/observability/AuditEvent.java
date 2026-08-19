package io.github.ashishgituser.mcpgateway.core.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * A single tool call decision, recorded regardless of whether it succeeded.
 *
 * @param arguments the call arguments after redaction, or null when argument capture is off (the
 *     default)
 */
public record AuditEvent(
    Instant timestamp,
    String principal,
    String toolName,
    AuditOutcome outcome,
    Duration duration,
    String reason,
    Map<String, Object> arguments) {

  public AuditEvent(
      Instant timestamp,
      String principal,
      String toolName,
      AuditOutcome outcome,
      Duration duration,
      String reason) {
    this(timestamp, principal, toolName, outcome, duration, reason, null);
  }
}
