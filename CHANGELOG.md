# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Multi-module Maven project scaffolding: `mcp-gateway-core`, `mcp-gateway-autoconfigure`, `mcp-gateway-spring-boot-starter`, `mcp-gateway-sample`.
- CI workflow building and testing against Java 17 and 21.
- Spotless formatting check wired into the build.
- Multi-server routing and aggregation: the gateway connects to every configured upstream MCP server, namespaces their tools as `<serverId>__<toolName>` to avoid collisions, and exposes the merged tool list over a single streamable-HTTP MCP endpoint (`mcp.gateway.mcp-endpoint`, default `/mcp`).
- `mcp.gateway.*` configuration properties for declaring upstream servers and their per-server request timeout.
