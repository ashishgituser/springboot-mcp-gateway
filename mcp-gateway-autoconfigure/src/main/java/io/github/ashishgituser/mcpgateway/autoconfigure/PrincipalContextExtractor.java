package io.github.ashishgituser.mcpgateway.autoconfigure;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Resolves the caller for each incoming HTTP request and hands it to the MCP SDK as transport
 * context, so it is available inside the tool call handler via {@code exchange.transportContext()}
 * — the only place the SDK lets request-scoped data reach a call handler.
 */
public class PrincipalContextExtractor implements McpTransportContextExtractor<HttpServletRequest> {

  private final PrincipalResolver principalResolver;

  public PrincipalContextExtractor(PrincipalResolver principalResolver) {
    this.principalResolver = principalResolver;
  }

  @Override
  public McpTransportContext extract(HttpServletRequest request) {
    GatewayPrincipal principal = principalResolver.resolve(request);
    return McpTransportContext.create(Map.of(GatewayPrincipal.CONTEXT_KEY, principal));
  }
}
