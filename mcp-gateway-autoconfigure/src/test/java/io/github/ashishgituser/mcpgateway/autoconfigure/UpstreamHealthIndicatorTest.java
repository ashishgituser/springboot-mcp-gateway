package io.github.ashishgituser.mcpgateway.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

@ExtendWith(MockitoExtension.class)
class UpstreamHealthIndicatorTest {

  @Mock private McpSyncClient filesystemClient;
  @Mock private McpSyncClient databaseClient;

  @Test
  void reportsUpWhenEveryUpstreamRespondsToPing() {
    UpstreamHealthIndicator indicator =
        new UpstreamHealthIndicator(
            List.of(
                new UpstreamServer("fs", filesystemClient),
                new UpstreamServer("db", databaseClient)));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("fs", "UP").containsEntry("db", "UP");
  }

  @Test
  void reportsDownWhenAnyUpstreamFailsToPing() {
    doThrow(new RuntimeException("connection refused")).when(databaseClient).ping();
    UpstreamHealthIndicator indicator =
        new UpstreamHealthIndicator(
            List.of(
                new UpstreamServer("fs", filesystemClient),
                new UpstreamServer("db", databaseClient)));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("fs", "UP");
    assertThat(health.getDetails().get("db")).asString().contains("connection refused");
  }
}
