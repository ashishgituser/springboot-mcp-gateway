package io.github.ashishgituser.mcpgateway.core.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GatewayRouterTest {

  private static final Map<String, Object> EMPTY_SCHEMA = Map.of("type", "object");

  @Mock private McpSyncClient filesystemClient;

  private GatewayRouter router;

  @BeforeEach
  void setUp() {
    when(filesystemClient.listTools())
        .thenReturn(
            ListToolsResult.builder(List.of(Tool.builder("readFile", EMPTY_SCHEMA).build()))
                .build());

    ToolRegistry registry = new ToolRegistry(List.of(new UpstreamServer("fs", filesystemClient)));
    registry.refresh();
    router = new GatewayRouter(registry);
  }

  @Test
  void forwardsTheCallToTheOwningUpstreamWithTheOriginalToolName() {
    CallToolResult upstreamResult =
        CallToolResult.builder().addTextContent("file contents").build();
    when(filesystemClient.callTool(any())).thenReturn(upstreamResult);

    CallToolResult result =
        router.callTool(
            CallToolRequest.builder("fs__readFile").arguments(Map.of("path", "/tmp/a")).build());

    ArgumentCaptor<CallToolRequest> forwarded = ArgumentCaptor.forClass(CallToolRequest.class);
    verify(filesystemClient).callTool(forwarded.capture());
    assertThat(forwarded.getValue().name()).isEqualTo("readFile");
    assertThat(forwarded.getValue().arguments()).containsEntry("path", "/tmp/a");
    assertThat(result).isEqualTo(upstreamResult);
  }

  @Test
  void rejectsCallsForUnregisteredTools() {
    assertThatThrownBy(
            () -> router.callTool(CallToolRequest.builder("fs__deleteEverything").build()))
        .isInstanceOf(ToolNotFoundException.class);
  }
}
