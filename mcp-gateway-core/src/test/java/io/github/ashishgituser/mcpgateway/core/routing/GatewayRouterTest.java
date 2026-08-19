package io.github.ashishgituser.mcpgateway.core.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.ashishgituser.mcpgateway.core.observability.AuditEvent;
import io.github.ashishgituser.mcpgateway.core.observability.AuditOutcome;
import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyDecision;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyDeniedException;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyEngine;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimitDecision;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimitExceededException;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimiter;
import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayList;
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

  @Test
  void recordsAnAuditEventAndAMetricForAnAllowedCall() {
    List<AuditEvent> events = new ArrayList<>();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ObservationRegistry observationRegistry = ObservationRegistry.create();
    observationRegistry
        .observationConfig()
        .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
    GatewayRouter router =
        new GatewayRouter(
            registry,
            PolicyEngine.permitAll(),
            RateLimiter.unlimited(),
            observationRegistry,
            events::add);
    when(filesystemClient.callTool(any()))
        .thenReturn(CallToolResult.builder().addTextContent("file contents").build());

    router.callTool(CallToolRequest.builder("fs__readFile").build(), CALLER);

    assertThat(events).hasSize(1);
    AuditEvent event = events.get(0);
    assertThat(event.principal()).isEqualTo("alice");
    assertThat(event.toolName()).isEqualTo("fs__readFile");
    assertThat(event.outcome()).isEqualTo(AuditOutcome.ALLOWED);
    assertThat(event.reason()).isNull();

    assertThat(meterRegistry.get("mcp.gateway.tool.call").tag("outcome", "allowed").timer().count())
        .isEqualTo(1);
  }

  @Test
  void recordsADeniedAuditEventWithoutCallingTheUpstream() {
    List<AuditEvent> events = new ArrayList<>();
    PolicyEngine denyEverything =
        (principal, toolName) -> PolicyDecision.deny("test policy denies everything");
    GatewayRouter router =
        new GatewayRouter(
            registry,
            denyEverything,
            RateLimiter.unlimited(),
            ObservationRegistry.NOOP,
            events::add);

    assertThatThrownBy(
        () -> router.callTool(CallToolRequest.builder("fs__readFile").build(), CALLER));

    assertThat(events).hasSize(1);
    assertThat(events.get(0).outcome()).isEqualTo(AuditOutcome.DENIED);
    assertThat(events.get(0).reason()).contains("test policy denies everything");
  }

  @Test
  void recordsANotFoundAuditEventForAnUnregisteredTool() {
    List<AuditEvent> events = new ArrayList<>();
    GatewayRouter router =
        new GatewayRouter(
            registry,
            PolicyEngine.permitAll(),
            RateLimiter.unlimited(),
            ObservationRegistry.NOOP,
            events::add);

    assertThatThrownBy(
        () -> router.callTool(CallToolRequest.builder("fs__deleteEverything").build(), CALLER));

    assertThat(events).hasSize(1);
    assertThat(events.get(0).outcome()).isEqualTo(AuditOutcome.NOT_FOUND);
  }

  @Test
  void deniesTheCallWithoutReachingTheUpstreamWhenRateLimitIsExceeded() {
    List<AuditEvent> events = new ArrayList<>();
    RateLimiter alwaysExhausted =
        (principal, toolName) ->
            RateLimitDecision.deny(java.time.Duration.ofSeconds(30), "quota exhausted");
    GatewayRouter router =
        new GatewayRouter(
            registry,
            PolicyEngine.permitAll(),
            alwaysExhausted,
            ObservationRegistry.NOOP,
            events::add);

    assertThatThrownBy(
            () -> router.callTool(CallToolRequest.builder("fs__readFile").build(), CALLER))
        .isInstanceOf(RateLimitExceededException.class)
        .hasMessageContaining("fs__readFile")
        .hasMessageContaining("alice");
    verify(filesystemClient, never()).callTool(any());
    assertThat(events).hasSize(1);
    assertThat(events.get(0).outcome()).isEqualTo(AuditOutcome.RATE_LIMITED);
  }
}
