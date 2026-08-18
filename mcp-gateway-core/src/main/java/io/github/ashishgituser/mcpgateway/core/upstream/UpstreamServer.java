package io.github.ashishgituser.mcpgateway.core.upstream;

import io.modelcontextprotocol.client.McpSyncClient;

/** A connected upstream MCP server the gateway proxies calls to. */
public record UpstreamServer(String id, McpSyncClient client) {}
