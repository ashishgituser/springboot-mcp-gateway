package io.github.ashishgituser.mcpgateway.core.routing;

public class ToolNotFoundException extends RuntimeException {

  public ToolNotFoundException(String toolName) {
    super("No upstream registered for tool: " + toolName);
  }
}
