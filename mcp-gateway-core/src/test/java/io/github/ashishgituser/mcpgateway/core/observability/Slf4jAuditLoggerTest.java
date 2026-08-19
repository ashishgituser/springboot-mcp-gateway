package io.github.ashishgituser.mcpgateway.core.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class Slf4jAuditLoggerTest {

  @Test
  void rendersAnAuditEventAsAJsonLine() {
    AuditEvent event =
        new AuditEvent(
            Instant.parse("2026-08-19T00:00:00Z"),
            "alice",
            "fs__readFile",
            AuditOutcome.ALLOWED,
            Duration.ofMillis(12),
            null);

    String json = Slf4jAuditLogger.toJson(event);

    assertThat(json)
        .isEqualTo(
            "{\"timestamp\":\"2026-08-19T00:00:00Z\",\"principal\":\"alice\","
                + "\"tool\":\"fs__readFile\",\"outcome\":\"ALLOWED\",\"durationMs\":12,"
                + "\"reason\":null}");
  }

  @Test
  void escapesQuotesAndBackslashesInTheReason() {
    AuditEvent event =
        new AuditEvent(
            Instant.parse("2026-08-19T00:00:00Z"),
            "alice",
            "fs__readFile",
            AuditOutcome.DENIED,
            Duration.ZERO,
            "denied by rule \"admin-only\" (path C:\\secrets)");

    String json = Slf4jAuditLogger.toJson(event);

    assertThat(json)
        .contains("\"reason\":\"denied by rule \\\"admin-only\\\" (path C:\\\\secrets)\"");
  }
}
