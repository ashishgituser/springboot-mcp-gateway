package io.github.ashishgituser.mcpgateway.core.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolNamespacingTest {

  @Test
  void prefixesToolNameWithServerId() {
    assertThat(ToolNamespacing.namespace("filesystem", "readFile"))
        .isEqualTo("filesystem__readFile");
  }

  @Test
  void keepsSeparatorsInsideTheToolNameIntact() {
    assertThat(ToolNamespacing.namespace("db", "read__write")).isEqualTo("db__read__write");
  }
}
