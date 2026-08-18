package io.github.ashishgituser.mcpgateway.core.routing;

import io.github.ashishgituser.mcpgateway.core.routing.ToolRegistry.RegisteredTool;
import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Dispatches a namespaced tool call to the upstream server that owns it. */
public class GatewayRouter {

  private final ToolRegistry toolRegistry;

  public GatewayRouter(ToolRegistry toolRegistry) {
    this.toolRegistry = toolRegistry;
  }

  public CallToolResult callTool(CallToolRequest request) {
    String namespacedName = request.name();
    RegisteredTool registeredTool =
        toolRegistry
            .find(namespacedName)
            .orElseThrow(() -> new ToolNotFoundException(namespacedName));
    UpstreamServer upstreamServer =
        toolRegistry
            .upstreamServer(registeredTool.upstreamServerId())
            .orElseThrow(() -> new ToolNotFoundException(namespacedName));

    CallToolRequest forwarded =
        CallToolRequest.builder(registeredTool.originalToolName())
            .arguments(request.arguments())
            .meta(request.meta())
            .build();
    return upstreamServer.client().callTool(forwarded);
  }
}
