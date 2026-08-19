package io.github.ashishgituser.mcpgateway.autoconfigure;

import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Reports UP/DOWN per configured upstream, by pinging each one's MCP session. */
public class UpstreamHealthIndicator implements HealthIndicator {

  private final List<UpstreamServer> upstreamServers;

  public UpstreamHealthIndicator(List<UpstreamServer> upstreamServers) {
    this.upstreamServers = upstreamServers;
  }

  @Override
  public Health health() {
    Map<String, String> details = new LinkedHashMap<>();
    boolean allUp = true;
    for (UpstreamServer server : upstreamServers) {
      try {
        server.ping();
        details.put(server.id(), "UP");
      } catch (RuntimeException e) {
        allUp = false;
        details.put(server.id(), "DOWN: " + e.getMessage());
      }
    }
    Health.Builder builder = allUp ? Health.up() : Health.down();
    details.forEach(builder::withDetail);
    return builder.build();
  }
}
