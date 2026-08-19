package io.github.ashishgituser.mcpgateway.core.upstream;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A connected upstream MCP server the gateway proxies calls to.
 *
 * <p>The connection is established on first use rather than at startup, so an upstream that is
 * down, slow to boot, or deployed after the gateway does not stop the gateway from serving the
 * others. Failures trip a {@link CircuitBreaker}, which is what keeps one unreachable upstream from
 * costing every caller a full request timeout.
 */
public class UpstreamServer {

  private static final Logger logger = LoggerFactory.getLogger(UpstreamServer.class);

  private final String id;
  private final Supplier<McpSyncClient> connector;
  private final CircuitBreaker circuitBreaker;

  private volatile McpSyncClient client;

  /** Wraps a client that is already connected — the shape unit tests and custom wiring use. */
  public UpstreamServer(String id, McpSyncClient client) {
    this.id = id;
    this.connector = () -> client;
    this.circuitBreaker = new CircuitBreaker();
    this.client = client;
  }

  public UpstreamServer(String id, Supplier<McpSyncClient> connector) {
    this(id, connector, new CircuitBreaker());
  }

  public UpstreamServer(String id, Supplier<McpSyncClient> connector, CircuitBreaker breaker) {
    this.id = id;
    this.connector = connector;
    this.circuitBreaker = breaker;
  }

  public String id() {
    return id;
  }

  /** False while the breaker is open, i.e. while this upstream is known to be unreachable. */
  public boolean available() {
    return circuitBreaker.allowsRequest();
  }

  public List<Tool> listTools() {
    return guarded("tools/list", client -> client.listTools().tools());
  }

  public CallToolResult callTool(CallToolRequest request) {
    return guarded("tools/call", client -> client.callTool(request));
  }

  public List<Resource> listResources() {
    return guarded("resources/list", client -> client.listResources().resources());
  }

  public List<ResourceTemplate> listResourceTemplates() {
    return guarded(
        "resources/templates/list", client -> client.listResourceTemplates().resourceTemplates());
  }

  public ReadResourceResult readResource(ReadResourceRequest request) {
    return guarded("resources/read", client -> client.readResource(request));
  }

  public List<Prompt> listPrompts() {
    return guarded("prompts/list", client -> client.listPrompts().prompts());
  }

  public GetPromptResult getPrompt(GetPromptRequest request) {
    return guarded("prompts/get", client -> client.getPrompt(request));
  }

  public void ping() {
    guarded(
        "ping",
        client -> {
          client.ping();
          return null;
        });
  }

  public void close() {
    McpSyncClient current = client;
    if (current != null) {
      try {
        current.closeGracefully();
      } catch (RuntimeException e) {
        logger.debug("Closing upstream '{}' failed", id, e);
      }
    }
  }

  private <T> T guarded(String operation, Function<McpSyncClient, T> action) {
    if (!circuitBreaker.allowsRequest()) {
      throw new UpstreamUnavailableException(
          id, "circuit breaker is open after repeated failures on " + operation);
    }
    try {
      T result = action.apply(connect());
      circuitBreaker.recordSuccess();
      return result;
    } catch (McpError e) {
      // The upstream answered, it just answered with a protocol error. That says nothing about
      // whether it is reachable, so it must not count towards tripping the breaker.
      circuitBreaker.recordSuccess();
      throw e;
    } catch (RuntimeException e) {
      circuitBreaker.recordFailure();
      client = null;
      throw new UpstreamUnavailableException(id, operation + " failed", e);
    }
  }

  private McpSyncClient connect() {
    McpSyncClient existing = client;
    if (existing != null) {
      return existing;
    }
    synchronized (this) {
      if (client == null) {
        logger.info("Connecting to upstream MCP server '{}'", id);
        McpSyncClient created = connector.get();
        created.initialize();
        client = created;
      }
      return client;
    }
  }
}
