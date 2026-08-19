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
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ListPromptsResult;
import io.modelcontextprotocol.spec.McpSchema.ListResourceTemplatesResult;
import io.modelcontextprotocol.spec.McpSchema.ListResourcesResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import java.util.HashMap;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The gateway's MCP data plane: the JSON-RPC handlers a client session dispatches to.
 *
 * <p>The SDK's {@code McpServer} answers list requests from collections fixed when the server was
 * built, identical for every caller. A gateway cannot work that way — what it publishes is the
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

    McpRequestHandler<ListToolsResult> listTools = this::listTools;
    McpRequestHandler<CallToolResult> callTool = this::callTool;
    handlers.put(McpSchema.METHOD_TOOLS_LIST, listTools);
    handlers.put(McpSchema.METHOD_TOOLS_CALL, callTool);

    McpRequestHandler<ListPromptsResult> listPrompts = this::listPrompts;
    McpRequestHandler<GetPromptResult> getPrompt = this::getPrompt;
    handlers.put(McpSchema.METHOD_PROMPT_LIST, listPrompts);
    handlers.put(McpSchema.METHOD_PROMPT_GET, getPrompt);

    McpRequestHandler<ListResourcesResult> listResources = this::listResources;
    McpRequestHandler<ListResourceTemplatesResult> listTemplates = this::listResourceTemplates;
    McpRequestHandler<ReadResourceResult> readResource = this::readResource;
    handlers.put(McpSchema.METHOD_RESOURCES_LIST, listResources);
    handlers.put(McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, listTemplates);
    handlers.put(McpSchema.METHOD_RESOURCES_READ, readResource);
    return handlers;
  }

  public Map<String, McpNotificationHandler> notificationHandlers() {
    Map<String, McpNotificationHandler> handlers = new HashMap<>();
    handlers.put(McpSchema.METHOD_NOTIFICATION_INITIALIZED, (exchange, params) -> Mono.empty());
    return handlers;
  }

  private Mono<ListToolsResult> listTools(McpAsyncServerExchange exchange, Object params) {
    return blocking(() -> ListToolsResult.builder(router.listTools(callerOf(exchange))).build());
  }

  private Mono<CallToolResult> callTool(McpAsyncServerExchange exchange, Object params) {
    CallToolRequest request = jsonMapper.convertValue(params, new TypeRef<CallToolRequest>() {});
    return blocking(() -> router.callTool(request, callerOf(exchange)));
  }

  private Mono<ListPromptsResult> listPrompts(McpAsyncServerExchange exchange, Object params) {
    return blocking(
        () -> new ListPromptsResult(router.listPrompts(callerOf(exchange)), null, null));
  }

  private Mono<GetPromptResult> getPrompt(McpAsyncServerExchange exchange, Object params) {
    GetPromptRequest request = jsonMapper.convertValue(params, new TypeRef<GetPromptRequest>() {});
    return blocking(() -> router.getPrompt(request, callerOf(exchange)));
  }

  private Mono<ListResourcesResult> listResources(McpAsyncServerExchange exchange, Object params) {
    return blocking(
        () -> new ListResourcesResult(router.listResources(callerOf(exchange)), null, null));
  }

  private Mono<ListResourceTemplatesResult> listResourceTemplates(
      McpAsyncServerExchange exchange, Object params) {
    return blocking(
        () ->
            new ListResourceTemplatesResult(
                router.listResourceTemplates(callerOf(exchange)), null, null));
  }

  private Mono<ReadResourceResult> readResource(McpAsyncServerExchange exchange, Object params) {
    ReadResourceRequest request =
        jsonMapper.convertValue(params, new TypeRef<ReadResourceRequest>() {});
    return blocking(() -> router.readResource(request, callerOf(exchange)));
  }

  /**
   * Offloaded to the elastic scheduler because the router blocks on the upstream MCP client — the
   * same treatment the SDK gives synchronous handlers.
   */
  private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> action) {
    return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
  }

  private static GatewayPrincipal callerOf(McpAsyncServerExchange exchange) {
    Object principal = exchange.transportContext().get(GatewayPrincipal.CONTEXT_KEY);
    return principal instanceof GatewayPrincipal resolved ? resolved : GatewayPrincipal.ANONYMOUS;
  }
}
