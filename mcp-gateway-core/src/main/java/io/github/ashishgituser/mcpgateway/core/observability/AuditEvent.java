package io.github.ashishgituser.mcpgateway.core.observability;

import java.time.Duration;
import java.time.Instant;

/** A single tool call decision, recorded regardless of whether it succeeded. */
public record AuditEvent(
    Instant timestamp,
    String principal,
    String toolName,
    AuditOutcome outcome,
    Duration duration,
    String reason) {}
