package io.github.ashishgituser.mcpgateway.core.observability;

import io.github.ashishgituser.mcpgateway.core.util.Glob;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Masks sensitive tool arguments before they reach the audit log.
 *
 * <p>Auditing what an agent asked for is most of the value of an audit trail — "who called
 * database__query" is far less useful than knowing which query. But tool arguments are exactly
 * where credentials, tokens and personal data end up, and an audit log is usually shipped somewhere
 * with broader access than the gateway itself. So argument capture is opt-in, and when it is on,
 * keys matching any configured pattern are replaced rather than dropped: the log still shows the
 * argument was present.
 */
public final class ArgumentRedactor {

  public static final String MASK = "***";

  /**
   * Key patterns masked unless the deployment configures its own. Deliberately broad — over-masking
   * costs a little forensic detail, under-masking puts a secret in a log that is hard to unpublish.
   */
  public static final List<String> DEFAULT_PATTERNS =
      List.of(
          "*password*",
          "*passwd*",
          "*secret*",
          "*token*",
          "*credential*",
          "*apikey*",
          "*api_key*",
          "authorization");

  private final List<Glob> patterns;

  public ArgumentRedactor(List<String> keyPatterns) {
    this.patterns = keyPatterns.stream().map(p -> Glob.of(p.toLowerCase())).toList();
  }

  public static ArgumentRedactor withDefaults() {
    return new ArgumentRedactor(DEFAULT_PATTERNS);
  }

  /** Returns a copy of {@code arguments} with every matching key masked, nested maps included. */
  public Map<String, Object> redact(Map<String, Object> arguments) {
    if (arguments == null || arguments.isEmpty()) {
      return arguments;
    }
    Map<String, Object> redacted = new LinkedHashMap<>(arguments.size());
    arguments.forEach((key, value) -> redacted.put(key, redactValue(key, value)));
    return redacted;
  }

  private Object redactValue(String key, Object value) {
    if (matches(key)) {
      return MASK;
    }
    if (value instanceof Map<?, ?> nested) {
      Map<String, Object> asStringKeys = new LinkedHashMap<>(nested.size());
      nested.forEach((k, v) -> asStringKeys.put(String.valueOf(k), v));
      return redact(asStringKeys);
    }
    return value;
  }

  private boolean matches(String key) {
    String lowerCased = key == null ? "" : key.toLowerCase();
    return patterns.stream().anyMatch(pattern -> pattern.matches(lowerCased));
  }
}
