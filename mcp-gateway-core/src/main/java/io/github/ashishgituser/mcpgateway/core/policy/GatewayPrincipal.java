package io.github.ashishgituser.mcpgateway.core.policy;

import java.util.Collection;
import java.util.Set;

/**
 * The caller a tool invocation is attributed to. Resolved from the incoming request before the call
 * reaches the router, and carried to it through the MCP transport context under {@link
 * #CONTEXT_KEY}.
 */
public record GatewayPrincipal(String name, Set<String> roles) {

  /** Key this principal is stored under in the MCP transport context. */
  public static final String CONTEXT_KEY = "mcp.gateway.principal";

  /** Used when the request carries no authenticated caller. */
  public static final GatewayPrincipal ANONYMOUS = new GatewayPrincipal("anonymous", Set.of());

  public GatewayPrincipal {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Principal name must not be blank");
    }
    roles = roles == null ? Set.of() : Set.copyOf(roles);
  }

  public GatewayPrincipal(String name) {
    this(name, Set.of());
  }

  public boolean hasAnyRole(Collection<String> candidates) {
    return candidates.stream().anyMatch(roles::contains);
  }
}
