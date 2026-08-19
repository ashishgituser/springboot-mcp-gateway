package io.github.ashishgituser.mcpgateway.core.protocol;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import io.github.ashishgituser.mcpgateway.core.routing.GatewayRouter;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import java.util.HashMap;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The gateway's MCP data plane: the JSON-RPC handlers a client session dispatches to.
 *
 * <p>The SDK's {@code McpServer} answers {@code tools/list} from a list fixed when the server was
 * built, identical for every caller. A gateway cannot work that way — the catalog it serves is the
 * union of several upstreams, and which slice of it a caller may see is a policy decision made per
 * request. So the gateway builds its own handler map and hands it to the transport provider
 * directly, rather than registering itself as a tool server.
 */
public final class GatewayServerHandlers {

  private final GatewayRouter router;
  private final McpJsonMapper jsonMapper;

  public GatewayServerHandlers(GatewayRouter router) {
    this(router, McpJsonDefaults.getMapper());
  }

  public GatewayServerHandlers(GatewayRouter router, McpJsonMapper jsonMapper) {
    this.router = router;
    this.jsonMapper = jsonMapper;
  }

  public Map<String, McpRequestHandler<?>> requestHandlers() {
    Map<String, McpRequestHandler<?>> handlers = new HashMap<>();
    // Ping must answer with empty data rather than null.
    handlers.put(McpSchema.METHOD_PING, (exchange, params) -> Mono.just(Map.of()));
    McpRequestHandler<ListToolsResult> listHandler = this::listTools;
    McpRequestHandler<CallToolResult> callHandler = this::callTool;
    handlers.put(McpSchema.METHOD_TOOLS_LIST, listHandler);
    handlers.put(McpSchema.METHOD_TOOLS_CALL, callHandler);
    return handlers;
  }

  public Map<String, McpNotificationHandler> notificationHandlers() {
    Map<String, McpNotificationHandler> handlers = new HashMap<>();
    handlers.put(McpSchema.METHOD_NOTIFICATION_INITIALIZED, (exchange, params) -> Mono.empty());
    return handlers;
  }

  private Mono<ListToolsResult> listTools(McpAsyncServerExchange exchange, Object params) {
    return Mono.fromSupplier(
        () -> ListToolsResult.builder(router.listTools(callerOf(exchange))).build());
  }

  /**
   * Offloaded to the elastic scheduler because the router blocks on the upstream MCP client — the
   * same treatment the SDK gives synchronous tool handlers.
   */
  private Mono<CallToolResult> callTool(McpAsyncServerExchange exchange, Object params) {
    CallToolRequest request = jsonMapper.convertValue(params, new TypeRef<CallToolRequest>() {});
    return Mono.fromCallable(() -> router.callTool(request, callerOf(exchange)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private static GatewayPrincipal callerOf(McpAsyncServerExchange exchange) {
    Object principal = exchange.transportContext().get(GatewayPrincipal.CONTEXT_KEY);
    return principal instanceof GatewayPrincipal resolved ? resolved : GatewayPrincipal.ANONYMOUS;
  }
}
