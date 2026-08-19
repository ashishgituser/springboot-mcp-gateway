package io.github.ashishgituser.mcpgateway.core.observability;

/** Records the outcome of every tool call the gateway routes. */
@FunctionalInterface
public interface AuditLogger {

  void record(AuditEvent event);

  static AuditLogger noop() {
    return event -> {};
  }

  /**
   * Drops call arguments before they are recorded. The default, because arguments are where
   * credentials and personal data live and an audit log usually travels further than the gateway.
   */
  static AuditLogger withoutArguments(AuditLogger delegate) {
    return event ->
        delegate.record(
            new AuditEvent(
                event.timestamp(),
                event.principal(),
                event.toolName(),
                event.outcome(),
                event.duration(),
                event.reason(),
                null));
  }

  /** Records call arguments with sensitive keys masked by {@code redactor}. */
  static AuditLogger redacting(AuditLogger delegate, ArgumentRedactor redactor) {
    return event ->
        delegate.record(
            new AuditEvent(
                event.timestamp(),
                event.principal(),
                event.toolName(),
                event.outcome(),
                event.duration(),
                event.reason(),
                redactor.redact(event.arguments())));
  }
}
