package io.github.ashishgituser.mcpgateway.core.routing;

import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates resources from all configured upstream servers.
 *
 * <p>Unlike tools and prompts, resources are identified by a URI rather than a name, and a URI is
 * meaningful to the client — rewriting it to carry a server prefix would break clients that resolve
 * it, and there is no namespacing scheme the spec blesses. So URIs pass through unchanged and the
 * registry remembers which upstream owns each one. Two upstreams publishing the same URI is a
 * genuine configuration mistake, so it is logged loudly and the first one registered wins rather
 * than being silently shadowed on every refresh.
 */
public class ResourceRegistry {

  private static final Logger logger = LoggerFactory.getLogger(ResourceRegistry.class);

  private final Map<String, UpstreamServer> upstreamServersById;
  private final Map<String, RegisteredResource> resourcesByUri = new ConcurrentHashMap<>();
  private final List<ResourceTemplate> templates = new ArrayList<>();

  public ResourceRegistry(List<UpstreamServer> upstreamServers) {
    this.upstreamServersById =
        upstreamServers.stream().collect(Collectors.toMap(UpstreamServer::id, Function.identity()));
  }

  /**
   * Re-fetches resources and resource templates from every upstream, skipping any that cannot be
   * reached.
   *
   * @return true if the published set of resource URIs changed
   */
  public boolean refresh() {
    Map<String, RegisteredResource> refreshed = new ConcurrentHashMap<>();
    List<ResourceTemplate> refreshedTemplates = new ArrayList<>();
    for (UpstreamServer upstreamServer : upstreamServersById.values()) {
      try {
        for (Resource resource : upstreamServer.listResources()) {
          RegisteredResource previous =
              refreshed.putIfAbsent(
                  resource.uri(), new RegisteredResource(upstreamServer.id(), resource));
          if (previous != null) {
            logger.warn(
                "Resource URI '{}' is published by both '{}' and '{}'; keeping '{}'",
                resource.uri(),
                previous.upstreamServerId(),
                upstreamServer.id(),
                previous.upstreamServerId());
          }
        }
        refreshedTemplates.addAll(upstreamServer.listResourceTemplates());
      } catch (RuntimeException e) {
        // Upstreams that declare no resource capability answer with an error rather than an empty
        // list, so this is the normal path for tool-only servers, not just for outages.
        logger.debug("No resources from upstream '{}': {}", upstreamServer.id(), e.getMessage());
      }
    }
    boolean changed = !refreshed.keySet().equals(resourcesByUri.keySet());
    resourcesByUri.clear();
    resourcesByUri.putAll(refreshed);
    synchronized (templates) {
      templates.clear();
      templates.addAll(refreshedTemplates);
    }
    return changed;
  }

  public List<Resource> allResources() {
    return resourcesByUri.values().stream().map(RegisteredResource::resource).toList();
  }

  public List<ResourceTemplate> allResourceTemplates() {
    synchronized (templates) {
      return List.copyOf(templates);
    }
  }

  public Optional<RegisteredResource> find(String uri) {
    return Optional.ofNullable(resourcesByUri.get(uri));
  }

  Optional<UpstreamServer> upstreamServer(String id) {
    return Optional.ofNullable(upstreamServersById.get(id));
  }

  public record RegisteredResource(String upstreamServerId, Resource resource) {}
}
