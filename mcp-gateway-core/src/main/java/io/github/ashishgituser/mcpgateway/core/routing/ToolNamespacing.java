package io.github.ashishgituser.mcpgateway.core.routing;

/**
 * Prefixes upstream tool names with their server id so tools from different servers never collide.
 */
public final class ToolNamespacing {

  private static final String SEPARATOR = "__";

  private ToolNamespacing() {}

  public static String namespace(String serverId, String toolName) {
    return serverId + SEPARATOR + toolName;
  }
}
