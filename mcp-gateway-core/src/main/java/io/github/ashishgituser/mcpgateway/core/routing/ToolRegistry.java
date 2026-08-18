package io.github.ashishgituser.mcpgateway.core.routing;

import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Aggregates tools from all configured upstream servers under namespaced names. */
public class ToolRegistry {

  private final Map<String, UpstreamServer> upstreamServersById;
  private final Map<String, RegisteredTool> toolsByNamespacedName = new ConcurrentHashMap<>();

  public ToolRegistry(List<UpstreamServer> upstreamServers) {
    this.upstreamServersById =
        upstreamServers.stream().collect(Collectors.toMap(UpstreamServer::id, Function.identity()));
  }

  /** Re-fetches the tool list from every upstream server and rebuilds the namespaced index. */
  public void refresh() {
    Map<String, RegisteredTool> refreshed = new ConcurrentHashMap<>();
    for (UpstreamServer upstreamServer : upstreamServersById.values()) {
      for (Tool tool : upstreamServer.client().listTools().tools()) {
        String namespacedName = ToolNamespacing.namespace(upstreamServer.id(), tool.name());
        refreshed.put(
            namespacedName,
            new RegisteredTool(
                upstreamServer.id(), tool.name(), namespacedTool(tool, namespacedName)));
      }
    }
    toolsByNamespacedName.clear();
    toolsByNamespacedName.putAll(refreshed);
  }

  public List<Tool> allTools() {
    return toolsByNamespacedName.values().stream().map(RegisteredTool::tool).toList();
  }

  public Optional<RegisteredTool> find(String namespacedName) {
    return Optional.ofNullable(toolsByNamespacedName.get(namespacedName));
  }

  Optional<UpstreamServer> upstreamServer(String id) {
    return Optional.ofNullable(upstreamServersById.get(id));
  }

  private static Tool namespacedTool(Tool tool, String namespacedName) {
    return Tool.builder(namespacedName, tool.inputSchema())
        .title(tool.title())
        .description(tool.description())
        .outputSchema(tool.outputSchema())
        .annotations(tool.annotations())
        .icons(tool.icons())
        .meta(tool.meta())
        .build();
  }

  public record RegisteredTool(String upstreamServerId, String originalToolName, Tool tool) {}
}
