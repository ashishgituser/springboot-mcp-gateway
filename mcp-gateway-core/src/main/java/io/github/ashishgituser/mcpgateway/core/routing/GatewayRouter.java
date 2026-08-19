package io.github.ashishgituser.mcpgateway.core.routing;

import io.github.ashishgituser.mcpgateway.core.observability.AuditEvent;
import io.github.ashishgituser.mcpgateway.core.observability.AuditLogger;
import io.github.ashishgituser.mcpgateway.core.observability.AuditOutcome;
import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyDecision;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyDeniedException;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyEngine;
import io.github.ashishgituser.mcpgateway.core.routing.ToolRegistry.RegisteredTool;
import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.Duration;
import java.time.Instant;

/** Dispatches a namespaced tool call to the upstream server that owns it, once policy allows it. */
public class GatewayRouter {

  private static final String OBSERVATION_NAME = "mcp.gateway.tool.call";

  private final ToolRegistry toolRegistry;
  private final PolicyEngine policyEngine;
  private final ObservationRegistry observationRegistry;
  private final AuditLogger auditLogger;

  public GatewayRouter(ToolRegistry toolRegistry, PolicyEngine policyEngine) {
    this(toolRegistry, policyEngine, ObservationRegistry.NOOP, AuditLogger.noop());
  }

  public GatewayRouter(
      ToolRegistry toolRegistry,
      PolicyEngine policyEngine,
      ObservationRegistry observationRegistry,
      AuditLogger auditLogger) {
    this.toolRegistry = toolRegistry;
    this.policyEngine = policyEngine;
    this.observationRegistry = observationRegistry;
    this.auditLogger = auditLogger;
  }

  public CallToolResult callTool(CallToolRequest request, GatewayPrincipal principal) {
    String namespacedName = request.name();
    Instant startedAt = Instant.now();
    long startNanos = System.nanoTime();
    Observation observation =
        Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
            .lowCardinalityKeyValue("tool.name", namespacedName)
            .highCardinalityKeyValue("principal.name", principal.name())
            .start();
    try {
      // Checked before the registry lookup so a denial never depends on which tools happen to be
      // registered at that moment.
      PolicyDecision decision = policyEngine.evaluate(principal, namespacedName);
      if (!decision.allowed()) {
        throw new PolicyDeniedException(principal, namespacedName, decision);
      }

      RegisteredTool registeredTool =
          toolRegistry
              .find(namespacedName)
              .orElseThrow(() -> new ToolNotFoundException(namespacedName));
      UpstreamServer upstreamServer =
          toolRegistry
              .upstreamServer(registeredTool.upstreamServerId())
              .orElseThrow(() -> new ToolNotFoundException(namespacedName));

      CallToolRequest forwarded =
          CallToolRequest.builder(registeredTool.originalToolName())
              .arguments(request.arguments())
              .meta(request.meta())
              .build();
      CallToolResult result = upstreamServer.client().callTool(forwarded);

      observation.lowCardinalityKeyValue("outcome", "allowed");
      audit(startedAt, startNanos, principal, namespacedName, AuditOutcome.ALLOWED, null);
      return result;
    } catch (PolicyDeniedException e) {
      observation.lowCardinalityKeyValue("outcome", "denied").error(e);
      audit(startedAt, startNanos, principal, namespacedName, AuditOutcome.DENIED, e.getMessage());
      throw e;
    } catch (ToolNotFoundException e) {
      observation.lowCardinalityKeyValue("outcome", "not_found").error(e);
      audit(
          startedAt, startNanos, principal, namespacedName, AuditOutcome.NOT_FOUND, e.getMessage());
      throw e;
    } catch (RuntimeException e) {
      observation.lowCardinalityKeyValue("outcome", "error").error(e);
      audit(startedAt, startNanos, principal, namespacedName, AuditOutcome.ERROR, e.getMessage());
      throw e;
    } finally {
      observation.stop();
    }
  }

  private void audit(
      Instant startedAt,
      long startNanos,
      GatewayPrincipal principal,
      String toolName,
      AuditOutcome outcome,
      String reason) {
    Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
    auditLogger.record(
        new AuditEvent(startedAt, principal.name(), toolName, outcome, duration, reason));
  }
}
