package io.github.ashishgituser.mcpgateway.core.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UpstreamServerTest {

  private final McpSyncClient client = mock(McpSyncClient.class);

  @Test
  void doesNotOpenTheSessionUntilTheFirstCall() {
    AtomicInteger connectAttempts = new AtomicInteger();
    UpstreamServer server =
        new UpstreamServer(
            "fs",
            () -> {
              connectAttempts.incrementAndGet();
              return client;
            });

    assertThat(connectAttempts).hasValue(0);
    verifyNoInteractions(client);

    when(client.listTools()).thenReturn(ListToolsResult.builder(List.of(tool("read"))).build());
    server.listTools();

    assertThat(connectAttempts).hasValue(1);
    verify(client).initialize();
  }

  @Test
  void reusesTheSessionAcrossCalls() {
    AtomicInteger connectAttempts = new AtomicInteger();
    when(client.listTools()).thenReturn(ListToolsResult.builder(List.of(tool("read"))).build());
    UpstreamServer server =
        new UpstreamServer(
            "fs",
            () -> {
              connectAttempts.incrementAndGet();
              return client;
            });

    server.listTools();
    server.listTools();

    assertThat(connectAttempts).hasValue(1);
    verify(client, times(1)).initialize();
  }

  @Test
  void stopsAttemptingOnceTheBreakerHasOpened() {
    AtomicInteger connectAttempts = new AtomicInteger();
    UpstreamServer server =
        new UpstreamServer(
            "fs",
            () -> {
              connectAttempts.incrementAndGet();
              throw new IllegalStateException("connection refused");
            },
            new CircuitBreaker(2, Duration.ofMinutes(1)));

    assertThatThrownBy(server::listTools).isInstanceOf(UpstreamUnavailableException.class);
    assertThatThrownBy(server::listTools).isInstanceOf(UpstreamUnavailableException.class);
    assertThat(server.available()).isFalse();

    assertThatThrownBy(server::listTools)
        .isInstanceOf(UpstreamUnavailableException.class)
        .hasMessageContaining("circuit breaker is open");

    // the third call never touched the network.
    assertThat(connectAttempts).hasValue(2);
  }

  @Test
  void protocolErrorsFromAReachableUpstreamDoNotTripTheBreaker() {
    when(client.listTools()).thenThrow(McpError.builder(-32000).message("no tools").build());
    UpstreamServer server =
        new UpstreamServer("fs", () -> client, new CircuitBreaker(1, Duration.ofMinutes(1)));

    assertThatThrownBy(server::listTools).isInstanceOf(McpError.class);

    // The upstream answered - it is up, it just said no. Treating that as a connectivity failure
    // would take a healthy server out of rotation.
    assertThat(server.available()).isTrue();
  }

  @Test
  void recoversOnceTheBreakerWindowHasPassed() {
    AtomicInteger attempts = new AtomicInteger();
    UpstreamServer server =
        new UpstreamServer(
            "fs",
            () -> {
              if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("connection refused");
              }
              return client;
            },
            new CircuitBreaker(1, Duration.ZERO));
    when(client.listTools()).thenReturn(ListToolsResult.builder(List.of(tool("read"))).build());

    assertThatThrownBy(server::listTools).isInstanceOf(UpstreamUnavailableException.class);

    assertThat(server.listTools()).extracting(Tool::name).containsExactly("read");
    assertThat(server.available()).isTrue();
  }

  private static Tool tool(String name) {
    return Tool.builder(name, Map.of("type", "object")).build();
  }
}
