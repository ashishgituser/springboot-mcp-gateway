package io.github.ashishgituser.mcpgateway.autoconfigure;

import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * Split out from {@link McpGatewayAutoConfiguration} because a class-level {@code
 * ConditionalOnClass} is required here: referencing {@link HealthIndicator} in a {@code @Bean}
 * method signature forces the JVM to resolve that type whenever Spring reflects over the declaring
 * class's methods (e.g. while evaluating an unrelated {@code @ConditionalOnMissingBean} in the same
 * class) — which throws {@code NoClassDefFoundError} for apps that never added Actuator, even
 * though the bean method itself was never going to be registered.
 */
@AutoConfiguration(after = McpGatewayAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(
    prefix = "mcp.gateway",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class UpstreamHealthAutoConfiguration {

  /** Registered as the "upstreams" health group, pinging every upstream's MCP session. */
  @Bean
  @ConditionalOnMissingBean(name = "upstreamsHealthIndicator")
  public HealthIndicator upstreamsHealthIndicator(List<UpstreamServer> upstreamServers) {
    return new UpstreamHealthIndicator(upstreamServers);
  }
}
