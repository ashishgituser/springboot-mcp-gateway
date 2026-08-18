package io.github.ashishgituser.mcpgateway.core.upstream;

import java.time.Duration;

/** Transport-agnostic description of an upstream MCP server to connect to. */
public record UpstreamServerDefinition(String id, String endpoint, Duration requestTimeout) {

  public UpstreamServerDefinition(String id, String endpoint) {
    this(id, endpoint, Duration.ofSeconds(10));
  }
}
