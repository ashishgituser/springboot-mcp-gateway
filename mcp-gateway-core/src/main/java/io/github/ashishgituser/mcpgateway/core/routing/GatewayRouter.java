package io.github.ashishgituser.mcpgateway.core.routing;

import io.github.ashishgituser.mcpgateway.core.observability.AuditEvent;
import io.github.ashishgituser.mcpgateway.core.observability.AuditLogger;
import io.github.ashishgituser.mcpgateway.core.observability.AuditOutcome;
import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyDecision;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyDeniedException;
import io.github.ashishgituser.mcpgateway.core.policy.PolicyEngine;
import io.github.ashishgituser.mcpgateway.core.policy.ToolVisibility;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimitDecision;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimitExceededException;
import io.github.ashishgituser.mcpgateway.core.ratelimit.RateLimiter;
import io.github.ashishgituser.mcpgateway.core.routing.ToolRegistry.RegisteredTool;
import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Dispatches a namespaced tool call to the upstream server that owns it, once policy allows it. */
public class GatewayRouter {

  private static final String CALL_OBSERVATION_NAME = "mcp.gateway.tool.call";
  private static final String LIST_OBSERVATION_NAME = "mcp.gateway.tool.list";

  private final ToolRegistry toolRegistry;
  private final PolicyEngine policyEngine;
  private final RateLimiter rateLimiter;
  private final ObservationRegistry observationRegistry;
  private final AuditLogger auditLogger;
  private final ToolVisibility toolVisibility;

  public GatewayRouter(ToolRegistry toolRegistry, PolicyEngine policyEngine) {
    this(
        toolRegistry,
        policyEngine,
        RateLimiter.unlimited(),
        ObservationRegistry.NOOP,
        AuditLogger.noop());
  }

  public GatewayRouter(
      ToolRegistry toolRegistry,
      PolicyEngine policyEngine,
      RateLimiter rateLimiter,
      ObservationRegistry observationRegistry,
      AuditLogger auditLogger) {
    this(
        toolRegistry,
        policyEngine,
        rateLimiter,
        observationRegistry,
        auditLogger,
        ToolVisibility.unrestricted());
  }

  public GatewayRouter(
      ToolRegistry toolRegistry,
      PolicyEngine policyEngine,
      RateLimiter rateLimiter,
      ObservationRegistry observationRegistry,
      AuditLogger auditLogger,
      ToolVisibility toolVisibility) {
    this.toolRegistry = toolRegistry;
    this.policyEngine = policyEngine;
    this.rateLimiter = rateLimiter;
    this.observationRegistry = observationRegistry;
    this.auditLogger = auditLogger;
    this.toolVisibility = toolVisibility;
  }

  /**
   * The slice of the aggregated catalog this caller is allowed to discover. Every tool it returns
   * is one the caller could also invoke, so a client is never shown a tool it would only ever be
   * denied on.
   */
  public List<Tool> listTools(GatewayPrincipal principal) {
    List<Tool> allTools = toolRegistry.allTools();
    List<Tool> visible = toolVisibility.visibleTo(principal, allTools);
    Observation.createNotStarted(LIST_OBSERVATION_NAME, observationRegistry)
        .highCardinalityKeyValue("principal.name", principal.name())
        .lowCardinalityKeyValue("filtered", Boolean.toString(visible.size() < allTools.size()))
        .observe(() -> {});
    return visible;
  }

  public CallToolResult callTool(CallToolRequest request, GatewayPrincipal principal) {
    String namespacedName = request.name();
    Map<String, Object> arguments = request.arguments();
    Instant startedAt = Instant.now();
    long startNanos = System.nanoTime();
    Observation observation =
        Observation.createNotStarted(CALL_OBSERVATION_NAME, observationRegistry)
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

      RateLimitDecision rateLimitDecision = rateLimiter.checkLimit(principal, namespacedName);
      if (!rateLimitDecision.allowed()) {
        throw new RateLimitExceededException(principal, namespacedName, rateLimitDecision);
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
      CallToolResult result = upstreamServer.callTool(forwarded);

      observation.lowCardinalityKeyValue("outcome", "allowed");
      audit(
          startedAt, startNanos, principal, namespacedName, arguments, AuditOutcome.ALLOWED, null);
      return result;
    } catch (PolicyDeniedException e) {
      observation.lowCardinalityKeyValue("outcome", "denied").error(e);
      audit(
          startedAt,
          startNanos,
          principal,
          namespacedName,
          arguments,
          AuditOutcome.DENIED,
          e.getMessage());
      throw e;
    } catch (RateLimitExceededException e) {
      observation.lowCardinalityKeyValue("outcome", "rate_limited").error(e);
      audit(
          startedAt,
          startNanos,
          principal,
          namespacedName,
          arguments,
          AuditOutcome.RATE_LIMITED,
          e.getMessage());
      throw e;
    } catch (ToolNotFoundException e) {
      observation.lowCardinalityKeyValue("outcome", "not_found").error(e);
      audit(
          startedAt,
          startNanos,
          principal,
          namespacedName,
          arguments,
          AuditOutcome.NOT_FOUND,
          e.getMessage());
      throw e;
    } catch (RuntimeException e) {
      observation.lowCardinalityKeyValue("outcome", "error").error(e);
      audit(
          startedAt,
          startNanos,
          principal,
          namespacedName,
          arguments,
          AuditOutcome.ERROR,
          e.getMessage());
      throw e;
    } finally {
      observation.stop();
    }
  }

  /**
   * Arguments are handed to the logger on every call; whether they are kept, masked or dropped is
   * the logger's decision (see {@link AuditLogger#withoutArguments} and {@link
   * AuditLogger#redacting}), so the router never has to know the audit policy.
   */
  private void audit(
      Instant startedAt,
      long startNanos,
      GatewayPrincipal principal,
      String toolName,
      Map<String, Object> arguments,
      AuditOutcome outcome,
      String reason) {
    Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
    auditLogger.record(
        new AuditEvent(
            startedAt, principal.name(), toolName, outcome, duration, reason, arguments));
  }
}
