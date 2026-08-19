package io.github.ashishgituser.mcpgateway.core.upstream;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;

/**
 * Describes how to reach an upstream MCP server over streamable HTTP. The session is opened by
 * {@link UpstreamServer} on first use rather than here, so building the gateway never blocks on an
 * upstream being up.
 */
public class UpstreamClientFactory {

  public UpstreamServer connect(UpstreamServerDefinition definition) {
    return new UpstreamServer(definition.id(), () -> newClient(definition));
  }

  private static McpSyncClient newClient(UpstreamServerDefinition definition) {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder(definition.endpoint()).build();
    return McpClient.sync(transport).requestTimeout(definition.requestTimeout()).build();
  }
}
