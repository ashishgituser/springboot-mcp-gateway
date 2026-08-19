package io.github.ashishgituser.mcpgateway.core.observability;

/** What happened to a tool call, for audit and metrics purposes. */
public enum AuditOutcome {
  ALLOWED,
  DENIED,
  RATE_LIMITED,
  NOT_FOUND,
  ERROR
}
