package io.github.ashishgituser.mcpgateway.core.routing;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyDecision;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyDeniedException;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyEngine;
import io.github.ashishgituser.mcpgateway.core.routing.ToolRegistry.RegisteredTool;
import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Dispatches a namespaced tool call to the upstream server that owns it, once policy allows it. */
public class GatewayRouter {

  private final ToolRegistry toolRegistry;
  private final PolicyEngine policyEngine;

  public GatewayRouter(ToolRegistry toolRegistry, PolicyEngine policyEngine) {
    this.toolRegistry = toolRegistry;
    this.policyEngine = policyEngine;
  }

  public CallToolResult callTool(CallToolRequest request, GatewayPrincipal principal) {
    String namespacedName = request.name();

    // Checked before the registry lookup so a denial never depends on which tools happen to be
    // registered at that moment.
    PolicyDecision decision = policyEngine.evaluate(principal, namespacedName);
    if (!decision.allowed()) {
      throw new PolicyDeniedException(principal, namespacedName, decision);
    }

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
