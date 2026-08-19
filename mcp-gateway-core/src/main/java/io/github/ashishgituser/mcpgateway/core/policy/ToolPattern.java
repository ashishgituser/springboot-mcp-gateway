package io.github.ashishgituser.mcpgateway.core.policy;

import io.github.ashishgituser.mcpgateway.core.util.Glob;

/**
 * A glob over namespaced tool names, e.g. {@code database__*} or {@code filesystem__read?}. {@code
 * *} matches any run of characters, {@code ?} matches exactly one; everything else is literal.
 */
public final class ToolPattern {

  private final Glob glob;

  private ToolPattern(Glob glob) {
    this.glob = glob;
  }

  public static ToolPattern of(String glob) {
    if (glob == null || glob.isBlank()) {
      throw new IllegalArgumentException("Tool pattern must not be blank");
    }
    return new ToolPattern(Glob.of(glob));
  }

  public boolean matches(String toolName) {
    return glob.matches(toolName);
  }

  @Override
  public String toString() {
    return glob.toString();
  }
}
