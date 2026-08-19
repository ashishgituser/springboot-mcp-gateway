package io.github.ashishgituser.mcpgateway.core.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A glob pattern: {@code *} matches any run of characters, {@code ?} matches exactly one, and
 * everything else is literal. Used for both tool-name rules and audit key matching, so it lives
 * apart from either.
 */
public final class Glob {

  private static final Pattern WILDCARD = Pattern.compile("[*?]");

  private final String glob;
  private final Pattern regex;

  private Glob(String glob) {
    this.glob = glob;
    this.regex = Pattern.compile(toRegex(glob));
  }

  public static Glob of(String glob) {
    if (glob == null || glob.isBlank()) {
      throw new IllegalArgumentException("Glob pattern must not be blank");
    }
    return new Glob(glob.trim());
  }

  public boolean matches(String candidate) {
    return candidate != null && regex.matcher(candidate).matches();
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
