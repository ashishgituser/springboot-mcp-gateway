package io.github.ashishgituser.mcpgateway.core.observability;

/** Records the outcome of every tool call the gateway routes. */
@FunctionalInterface
public interface AuditLogger {

  void record(AuditEvent event);

  static AuditLogger noop() {
    return event -> {};
  }
}
