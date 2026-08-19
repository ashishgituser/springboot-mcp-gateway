package io.github.ashishgituser.mcpgateway.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The gateway as a service.
 *
 * <p>Everything it does is configuration — upstreams, policy, quotas — so this deliberately has no
 * code beyond a main method. Teams that already have a Spring Boot application should add the
 * starter to it instead; this exists for the platform teams who want the gateway as its own
 * deployable next to the MCP servers it fronts.
 */
@SpringBootApplication
public class McpGatewayServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(McpGatewayServerApplication.class, args);
  }
}
