package io.github.ashishgituser.mcpgateway.core.protocol;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Answers the client's {@code initialize} request on behalf of the gateway, negotiating the
 * protocol version the same way the SDK's own server does: echo the client's version when it is one
 * we speak, otherwise offer the newest one we do.
 */
public final class GatewayInitRequestHandler
    implements McpStreamableServerSession.InitRequestHandler {

  private static final Logger logger = LoggerFactory.getLogger(GatewayInitRequestHandler.class);

  private final List<String> supportedProtocolVersions;
  private final McpSchema.Implementation serverInfo;
  private final McpSchema.ServerCapabilities capabilities;
  private final String instructions;

  public GatewayInitRequestHandler(
      List<String> supportedProtocolVersions,
      McpSchema.Implementation serverInfo,
      McpSchema.ServerCapabilities capabilities,
      String instructions) {
    if (supportedProtocolVersions == null || supportedProtocolVersions.isEmpty()) {
      throw new IllegalArgumentException("At least one supported protocol version is required");
    }
    this.supportedProtocolVersions = List.copyOf(supportedProtocolVersions);
    this.serverInfo = serverInfo;
    this.capabilities = capabilities;
    this.instructions = instructions;
  }

  @Override
  public Mono<McpSchema.InitializeResult> handle(McpSchema.InitializeRequest initializeRequest) {
    return Mono.fromSupplier(
        () -> {
          String requested = initializeRequest.protocolVersion();
          String negotiated =
              supportedProtocolVersions.contains(requested)
                  ? requested
                  : supportedProtocolVersions.get(supportedProtocolVersions.size() - 1);
          if (!negotiated.equals(requested)) {
            logger.warn(
                "Client {} requested unsupported MCP protocol version {}; responding with {}",
                initializeRequest.clientInfo(),
                requested,
                negotiated);
          }
          return new McpSchema.InitializeResult(negotiated, capabilities, serverInfo, instructions);
        });
  }
}
