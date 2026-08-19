package io.github.ashishgituser.mcpgateway.core.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes each audit event as a single structured JSON log line, so it can be routed to its own file
 * or sink independently of application logs (e.g. via a dedicated logback appender on the {@code
 * io.github.ashishgituser.mcpgateway.audit} logger name).
 */
public class Slf4jAuditLogger implements AuditLogger {

  private static final Logger log =
      LoggerFactory.getLogger("io.github.ashishgituser.mcpgateway.audit");

  @Override
  public void record(AuditEvent event) {
    log.info(toJson(event));
  }

  static String toJson(AuditEvent event) {
    StringBuilder json = new StringBuilder(128);
    json.append("{\"timestamp\":\"").append(event.timestamp()).append('"');
    json.append(",\"principal\":").append(quote(event.principal()));
    json.append(",\"tool\":").append(quote(event.toolName()));
    json.append(",\"outcome\":\"").append(event.outcome()).append('"');
    json.append(",\"durationMs\":").append(event.duration().toMillis());
    json.append(",\"reason\":").append(quote(event.reason()));
    json.append('}');
    return json.toString();
  }

  private static String quote(String value) {
    if (value == null) {
      return "null";
    }
    String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
    return "\"" + escaped + "\"";
  }
}
