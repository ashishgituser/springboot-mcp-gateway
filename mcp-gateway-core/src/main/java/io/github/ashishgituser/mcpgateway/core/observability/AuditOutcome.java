package io.github.ashishgituser.mcpgateway.core.observability;

/** What happened to a tool call, for audit and metrics purposes. */
public enum AuditOutcome {
  ALLOWED,
  DENIED,
  NOT_FOUND,
  ERROR
}
