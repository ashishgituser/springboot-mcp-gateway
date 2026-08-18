package io.github.ashishgituser.mcpgateway.autoconfigure;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "mcp.gateway")
public record McpGatewayProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("/mcp") String mcpEndpoint,
    List<Server> servers) {

  public McpGatewayProperties {
    if (servers == null) {
      servers = List.of();
    }
  }

  public record Server(String id, String endpoint, @DefaultValue("10s") Duration requestTimeout) {}
}
