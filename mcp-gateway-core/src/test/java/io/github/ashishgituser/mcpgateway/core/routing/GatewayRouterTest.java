package io.github.ashishgituser.mcpgateway.core.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyDecision;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyDeniedException;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyEngine;
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
  private static final GatewayPrincipal CALLER = new GatewayPrincipal("alice");

  @Mock private McpSyncClient filesystemClient;

  private ToolRegistry registry;

  @BeforeEach
  void setUp() {
    when(filesystemClient.listTools())
        .thenReturn(
            ListToolsResult.builder(List.of(Tool.builder("readFile", EMPTY_SCHEMA).build()))
                .build());

    registry = new ToolRegistry(List.of(new UpstreamServer("fs", filesystemClient)));
    registry.refresh();
  }

  @Test
  void forwardsAnAllowedCallToTheOwningUpstreamWithTheOriginalToolName() {
    GatewayRouter router = new GatewayRouter(registry, PolicyEngine.permitAll());
    CallToolResult upstreamResult =
        CallToolResult.builder().addTextContent("file contents").build();
    when(filesystemClient.callTool(any())).thenReturn(upstreamResult);

    CallToolResult result =
        router.callTool(
            CallToolRequest.builder("fs__readFile").arguments(Map.of("path", "/tmp/a")).build(),
            CALLER);

    ArgumentCaptor<CallToolRequest> forwarded = ArgumentCaptor.forClass(CallToolRequest.class);
    verify(filesystemClient).callTool(forwarded.capture());
    assertThat(forwarded.getValue().name()).isEqualTo("readFile");
    assertThat(forwarded.getValue().arguments()).containsEntry("path", "/tmp/a");
    assertThat(result).isEqualTo(upstreamResult);
  }

  @Test
  void rejectsCallsForUnregisteredTools() {
    GatewayRouter router = new GatewayRouter(registry, PolicyEngine.permitAll());

    assertThatThrownBy(
            () -> router.callTool(CallToolRequest.builder("fs__deleteEverything").build(), CALLER))
        .isInstanceOf(ToolNotFoundException.class);
  }

  @Test
  void deniesTheCallWithoutReachingTheUpstreamWhenPolicyRejectsIt() {
    PolicyEngine denyEverything =
        (principal, toolName) -> PolicyDecision.deny("test policy denies everything");
    GatewayRouter router = new GatewayRouter(registry, denyEverything);

    assertThatThrownBy(
            () -> router.callTool(CallToolRequest.builder("fs__readFile").build(), CALLER))
        .isInstanceOf(PolicyDeniedException.class)
        .hasMessageContaining("fs__readFile")
        .hasMessageContaining("alice");
    verify(filesystemClient, never()).callTool(any());
  }
}
