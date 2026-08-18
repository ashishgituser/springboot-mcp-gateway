package io.github.ashishgituser.mcpgateway.core.upstream;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;

/** Connects to an upstream MCP server over streamable HTTP and initializes the MCP session. */
public class UpstreamClientFactory {

  public UpstreamServer connect(UpstreamServerDefinition definition) {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder(definition.endpoint()).build();
    McpSyncClient client =
        McpClient.sync(transport).requestTimeout(definition.requestTimeout()).build();
    client.initialize();
    return new UpstreamServer(definition.id(), client);
  }
}
