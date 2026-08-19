package io.github.ashishgituser.mcpgateway.core.policy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A glob over namespaced tool names, e.g. {@code database__*} or {@code filesystem__read?}. {@code
 * *} matches any run of characters, {@code ?} matches exactly one; everything else is literal.
 */
public final class ToolPattern {

  private static final Pattern WILDCARD = Pattern.compile("[*?]");

  private final String glob;
  private final Pattern regex;

  private ToolPattern(String glob) {
    this.glob = glob;
    this.regex = Pattern.compile(toRegex(glob));
  }

  public static ToolPattern of(String glob) {
    if (glob == null || glob.isBlank()) {
      throw new IllegalArgumentException("Tool pattern must not be blank");
    }
    return new ToolPattern(glob.trim());
  }

  public boolean matches(String toolName) {
    return toolName != null && regex.matcher(toolName).matches();
  }

  private static String toRegex(String glob) {
    StringBuilder regex = new StringBuilder();
    Matcher wildcards = WILDCARD.matcher(glob);
    int literalStart = 0;
    while (wildcards.find()) {
      if (wildcards.start() > literalStart) {
        regex.append(Pattern.quote(glob.substring(literalStart, wildcards.start())));
      }
      regex.append("*".equals(wildcards.group()) ? ".*" : ".");
      literalStart = wildcards.end();
    }
    if (literalStart < glob.length()) {
      regex.append(Pattern.quote(glob.substring(literalStart)));
    }
    return regex.toString();
  }

  @Override
  public String toString() {
    return glob;
  }
}
