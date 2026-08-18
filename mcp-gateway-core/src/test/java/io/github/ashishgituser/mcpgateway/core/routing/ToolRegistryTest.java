package io.github.ashishgituser.mcpgateway.core.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolRegistryTest {

  @Mock private McpSyncClient filesystemClient;
  @Mock private McpSyncClient databaseClient;

  private static final Map<String, Object> EMPTY_SCHEMA = Map.of("type", "object");

  @Test
  void namespacesAndAggregatesToolsFromEveryUpstream() {
    when(filesystemClient.listTools())
        .thenReturn(
            ListToolsResult.builder(
                    List.of(
                        Tool.builder("readFile", EMPTY_SCHEMA).description("Reads a file").build()))
                .build());
    when(databaseClient.listTools())
        .thenReturn(
            ListToolsResult.builder(
                    List.of(
                        Tool.builder("query", EMPTY_SCHEMA).description("Runs a query").build()))
                .build());

    ToolRegistry registry =
        new ToolRegistry(
            List.of(
                new UpstreamServer("fs", filesystemClient),
                new UpstreamServer("db", databaseClient)));
    registry.refresh();

    assertThat(registry.allTools())
        .extracting(Tool::name)
        .containsExactlyInAnyOrder("fs__readFile", "db__query");

    ToolRegistry.RegisteredTool readFile = registry.find("fs__readFile").orElseThrow();
    assertThat(readFile.upstreamServerId()).isEqualTo("fs");
    assertThat(readFile.originalToolName()).isEqualTo("readFile");
  }

  @Test
  void refreshReplacesThePreviousToolSet() {
    when(filesystemClient.listTools())
        .thenReturn(
            ListToolsResult.builder(List.of(Tool.builder("readFile", EMPTY_SCHEMA).build()))
                .build())
        .thenReturn(
            ListToolsResult.builder(List.of(Tool.builder("writeFile", EMPTY_SCHEMA).build()))
                .build());

    ToolRegistry registry = new ToolRegistry(List.of(new UpstreamServer("fs", filesystemClient)));
    registry.refresh();
    registry.refresh();

    assertThat(registry.allTools()).extracting(Tool::name).containsExactly("fs__writeFile");
  }

  @Test
  void findReturnsEmptyForUnknownTool() {
    ToolRegistry registry = new ToolRegistry(List.of());
    assertThat(registry.find("nope__tool")).isEmpty();
  }
}
