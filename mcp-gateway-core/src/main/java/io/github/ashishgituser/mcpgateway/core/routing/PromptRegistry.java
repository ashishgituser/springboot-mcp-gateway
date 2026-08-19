package io.github.ashishgituser.mcpgateway.core.routing;

import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates prompts from all configured upstream servers under namespaced names.
 *
 * <p>Prompts are named the same way tools are, so they get the same {@code <serverId>__<name>}
 * treatment: two servers can both publish a {@code review} prompt without colliding, and a policy
 * rule can target a whole server with {@code github__*}.
 */
public class PromptRegistry {

  private static final Logger logger = LoggerFactory.getLogger(PromptRegistry.class);

  private final Map<String, UpstreamServer> upstreamServersById;
  private final Map<String, RegisteredPrompt> promptsByNamespacedName = new ConcurrentHashMap<>();

  public PromptRegistry(List<UpstreamServer> upstreamServers) {
    this.upstreamServersById =
        upstreamServers.stream().collect(Collectors.toMap(UpstreamServer::id, Function.identity()));
  }

  /**
   * Re-fetches prompts from every upstream, skipping any that cannot be reached.
   *
   * @return true if the published set of prompts changed
   */
  public boolean refresh() {
    Map<String, RegisteredPrompt> refreshed = new ConcurrentHashMap<>();
    for (UpstreamServer upstreamServer : upstreamServersById.values()) {
      try {
        for (Prompt prompt : upstreamServer.listPrompts()) {
          String namespacedName = ToolNamespacing.namespace(upstreamServer.id(), prompt.name());
          refreshed.put(
              namespacedName,
              new RegisteredPrompt(
                  upstreamServer.id(), prompt.name(), namespacedPrompt(prompt, namespacedName)));
        }
      } catch (RuntimeException e) {
        // An upstream that declares no prompt capability answers with an error rather than an empty
        // list, so this is the normal path for tool-only servers, not just for outages.
        logger.debug("No prompts from upstream '{}': {}", upstreamServer.id(), e.getMessage());
      }
    }
    boolean changed = !refreshed.keySet().equals(promptsByNamespacedName.keySet());
    promptsByNamespacedName.clear();
    promptsByNamespacedName.putAll(refreshed);
    return changed;
  }

  public List<Prompt> allPrompts() {
    return promptsByNamespacedName.values().stream().map(RegisteredPrompt::prompt).toList();
  }

  public Optional<RegisteredPrompt> find(String namespacedName) {
    return Optional.ofNullable(promptsByNamespacedName.get(namespacedName));
  }

  Optional<UpstreamServer> upstreamServer(String id) {
    return Optional.ofNullable(upstreamServersById.get(id));
  }

  private static Prompt namespacedPrompt(Prompt prompt, String namespacedName) {
    return new Prompt(
        namespacedName,
        prompt.title(),
        prompt.description(),
        prompt.arguments(),
        prompt.meta(),
        prompt.icons());
  }

  public record RegisteredPrompt(String upstreamServerId, String originalName, Prompt prompt) {}
}
