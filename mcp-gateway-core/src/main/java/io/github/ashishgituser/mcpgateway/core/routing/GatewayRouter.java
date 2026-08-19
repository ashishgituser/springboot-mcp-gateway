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
import io.github.ashishgituser.mcpgateway.core.routing.PromptRegistry.RegisteredPrompt;
import io.github.ashishgituser.mcpgateway.core.routing.ResourceRegistry.RegisteredResource;
import io.github.ashishgituser.mcpgateway.core.routing.ToolRegistry.RegisteredTool;
import io.github.ashishgituser.mcpgateway.core.upstream.UpstreamServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Dispatches a namespaced tool call to the upstream server that owns it, once policy allows it. */
public class GatewayRouter {

  private static final String CALL_OBSERVATION_NAME = "mcp.gateway.tool.call";
  private static final String LIST_OBSERVATION_NAME = "mcp.gateway.tool.list";
  private static final String PROMPT_OBSERVATION_NAME = "mcp.gateway.prompt.get";
  private static final String RESOURCE_OBSERVATION_NAME = "mcp.gateway.resource.read";

  private final ToolRegistry toolRegistry;
  private final PolicyEngine policyEngine;
  private final RateLimiter rateLimiter;
  private final ObservationRegistry observationRegistry;
  private final AuditLogger auditLogger;
  private final ToolVisibility toolVisibility;
  private final PromptRegistry promptRegistry;
  private final ResourceRegistry resourceRegistry;

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
    this(
        toolRegistry,
        new PromptRegistry(List.of()),
        new ResourceRegistry(List.of()),
        policyEngine,
        rateLimiter,
        observationRegistry,
        auditLogger,
        toolVisibility);
  }

  public GatewayRouter(
      ToolRegistry toolRegistry,
      PromptRegistry promptRegistry,
      ResourceRegistry resourceRegistry,
      PolicyEngine policyEngine,
      RateLimiter rateLimiter,
      ObservationRegistry observationRegistry,
      AuditLogger auditLogger,
      ToolVisibility toolVisibility) {
    this.promptRegistry = promptRegistry;
    this.resourceRegistry = resourceRegistry;
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
    List<Tool> visible =
        allTools.stream().filter(tool -> toolVisibility.isVisible(principal, tool.name())).toList();
    recordDiscovery(principal, allTools.size(), visible.size());
    return visible;
  }

  /** The prompts this caller may discover, namespaced and filtered exactly as tools are. */
  public List<Prompt> listPrompts(GatewayPrincipal principal) {
    List<Prompt> all = promptRegistry.allPrompts();
    List<Prompt> visible =
        all.stream().filter(prompt -> toolVisibility.isVisible(principal, prompt.name())).toList();
    recordDiscovery(principal, all.size(), visible.size());
    return visible;
  }

  /**
   * Resources are identified to policy by their URI, since that is what a client asks for and there
   * is no namespaced name to match on.
   */
  public List<Resource> listResources(GatewayPrincipal principal) {
    List<Resource> all = resourceRegistry.allResources();
    List<Resource> visible =
        all.stream()
            .filter(resource -> toolVisibility.isVisible(principal, resource.uri()))
            .toList();
    recordDiscovery(principal, all.size(), visible.size());
    return visible;
  }

  /**
   * Templates are URI patterns rather than concrete resources, so policy matches them on the
   * template string.
   */
  public List<ResourceTemplate> listResourceTemplates(GatewayPrincipal principal) {
    return resourceRegistry.allResourceTemplates().stream()
        .filter(template -> toolVisibility.isVisible(principal, template.uriTemplate()))
        .toList();
  }

  public GetPromptResult getPrompt(GetPromptRequest request, GatewayPrincipal principal) {
    String namespacedName = request.name();
    return invoke(
        PROMPT_OBSERVATION_NAME,
        namespacedName,
        principal,
        request.arguments(),
        () -> {
          RegisteredPrompt registered =
              promptRegistry
                  .find(namespacedName)
                  .orElseThrow(() -> new ToolNotFoundException(namespacedName));
          UpstreamServer upstream =
              promptRegistry
                  .upstreamServer(registered.upstreamServerId())
                  .orElseThrow(() -> new ToolNotFoundException(namespacedName));
          return upstream.getPrompt(
              new GetPromptRequest(registered.originalName(), request.arguments(), request.meta()));
        });
  }

  public ReadResourceResult readResource(ReadResourceRequest request, GatewayPrincipal principal) {
    String uri = request.uri();
    return invoke(
        RESOURCE_OBSERVATION_NAME,
        uri,
        principal,
        null,
        () -> {
          RegisteredResource registered =
              resourceRegistry.find(uri).orElseThrow(() -> new ToolNotFoundException(uri));
          UpstreamServer upstream =
              resourceRegistry
                  .upstreamServer(registered.upstreamServerId())
                  .orElseThrow(() -> new ToolNotFoundException(uri));
          return upstream.readResource(request);
        });
  }

  private void recordDiscovery(GatewayPrincipal principal, int total, int visible) {
    Observation.createNotStarted(LIST_OBSERVATION_NAME, observationRegistry)
        .highCardinalityKeyValue("principal.name", principal.name())
        .lowCardinalityKeyValue("filtered", Boolean.toString(visible < total))
        .observe(() -> {});
  }

  /**
   * Policy, then quota, then the upstream. The same order and the same failure semantics as a tool
   * call, so reaching for a prompt or a resource is never a way around a rule.
   */
  private <T> T invoke(
      String observationName,
      String capabilityName,
      GatewayPrincipal principal,
      Map<String, Object> arguments,
      Supplier<T> action) {
    Instant startedAt = Instant.now();
    long startNanos = System.nanoTime();
    Observation observation =
        Observation.createNotStarted(observationName, observationRegistry)
            .lowCardinalityKeyValue("capability.name", capabilityName)
            .highCardinalityKeyValue("principal.name", principal.name())
            .start();
    AuditOutcome outcome = AuditOutcome.ERROR;
    String reason = null;
    try {
      PolicyDecision decision = policyEngine.evaluate(principal, capabilityName);
      if (!decision.allowed()) {
        outcome = AuditOutcome.DENIED;
        throw new PolicyDeniedException(principal, capabilityName, decision);
      }
      RateLimitDecision rateLimitDecision = rateLimiter.checkLimit(principal, capabilityName);
      if (!rateLimitDecision.allowed()) {
        outcome = AuditOutcome.RATE_LIMITED;
        throw new RateLimitExceededException(principal, capabilityName, rateLimitDecision);
      }
      T result = action.get();
      outcome = AuditOutcome.ALLOWED;
      observation.lowCardinalityKeyValue("outcome", "allowed");
      return result;
    } catch (ToolNotFoundException e) {
      outcome = AuditOutcome.NOT_FOUND;
      reason = e.getMessage();
      observation.lowCardinalityKeyValue("outcome", "not_found").error(e);
      throw e;
    } catch (RuntimeException e) {
      reason = e.getMessage();
      observation.lowCardinalityKeyValue("outcome", outcome.name().toLowerCase()).error(e);
      throw e;
    } finally {
      audit(startedAt, startNanos, principal, capabilityName, arguments, outcome, reason);
      observation.stop();
    }
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
