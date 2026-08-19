package io.github.ashishgituser.mcpgateway.core.routing;

import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Aggregates tools from all configured upstream servers under namespaced names. */
public class ToolRegistry {

  private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);

  private final Map<String, UpstreamServer> upstreamServersById;
  private final Map<String, RegisteredTool> toolsByNamespacedName = new ConcurrentHashMap<>();

  public ToolRegistry(List<UpstreamServer> upstreamServers) {
    this.upstreamServersById =
        upstreamServers.stream().collect(Collectors.toMap(UpstreamServer::id, Function.identity()));
  }

  /**
   * Re-fetches the tool list from every upstream server and rebuilds the namespaced index. An
   * upstream that cannot be reached is logged and skipped rather than failing the refresh: the
   * gateway keeps serving the upstreams that are up, and picks the missing one back up on a later
   * refresh. Its tools disappear from the catalog while it is down, so the gateway never advertises
   * a tool it cannot currently route.
   *
   * @return true if the published catalog changed, i.e. clients should be told to re-list
   */
  public boolean refresh() {
    Map<String, RegisteredTool> refreshed = new ConcurrentHashMap<>();
    for (UpstreamServer upstreamServer : upstreamServersById.values()) {
      try {
        for (Tool tool : upstreamServer.listTools()) {
          String namespacedName = ToolNamespacing.namespace(upstreamServer.id(), tool.name());
          refreshed.put(
              namespacedName,
              new RegisteredTool(
                  upstreamServer.id(), tool.name(), namespacedTool(tool, namespacedName)));
        }
      } catch (RuntimeException e) {
        logger.warn(
            "Skipping upstream '{}' during catalog refresh: {}",
            upstreamServer.id(),
            e.getMessage());
      }
    }
    boolean changed = !refreshed.keySet().equals(toolsByNamespacedName.keySet());
    toolsByNamespacedName.clear();
    toolsByNamespacedName.putAll(refreshed);
    return changed;
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
