package io.github.ashishgituser.mcpgateway.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolPatternTest {

  @Test
  void matchesAnExactLiteralOnly() {
    ToolPattern pattern = ToolPattern.of("database__query");

    assertThat(pattern.matches("database__query")).isTrue();
    assertThat(pattern.matches("database__queryAll")).isFalse();
  }

  @Test
  void starMatchesAnyRunOfCharactersIncludingTheNamespaceSeparator() {
    ToolPattern pattern = ToolPattern.of("database__*");

    assertThat(pattern.matches("database__query")).isTrue();
    assertThat(pattern.matches("database__describeSchema")).isTrue();
    assertThat(pattern.matches("filesystem__query")).isFalse();
  }

  @Test
  void questionMarkMatchesExactlyOneCharacter() {
    ToolPattern pattern = ToolPattern.of("fs__read?ile");

    assertThat(pattern.matches("fs__readFile")).isTrue();
    assertThat(pattern.matches("fs__readFFile")).isFalse();
  }

  @Test
  void literalRegexCharactersInTheGlobAreTreatedAsPlainText() {
    ToolPattern pattern = ToolPattern.of("billing__charge($)");

    assertThat(pattern.matches("billing__charge($)")).isTrue();
  }
}
