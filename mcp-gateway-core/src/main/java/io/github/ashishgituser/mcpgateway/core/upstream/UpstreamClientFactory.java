package io.github.ashishgituser.mcpgateway.core.upstream;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.time.Duration;

/**
 * Describes how to reach an upstream MCP server over streamable HTTP. The session is opened by
 * {@link UpstreamServer} on first use rather than here, so building the gateway never blocks on an
 * upstream being up.
 */
public class UpstreamClientFactory {

  private final int failureThreshold;
  private final Duration openDuration;

  public UpstreamClientFactory() {
    this(CircuitBreaker.DEFAULT_FAILURE_THRESHOLD, CircuitBreaker.DEFAULT_OPEN_DURATION);
  }

  public UpstreamClientFactory(int failureThreshold, Duration openDuration) {
    this.failureThreshold = failureThreshold;
    this.openDuration = openDuration;
  }

  public UpstreamServer connect(UpstreamServerDefinition definition) {
    return new UpstreamServer(
        definition.id(),
        () -> newClient(definition),
        new CircuitBreaker(failureThreshold, openDuration));
  }

  private static McpSyncClient newClient(UpstreamServerDefinition definition) {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder(definition.endpoint()).build();
    return McpClient.sync(transport).requestTimeout(definition.requestTimeout()).build();
  }
}
