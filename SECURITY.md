# Security Policy

## Supported versions

This project hasn't cut a release yet — `main` is the only supported line until the first Maven Central release ships. Once released, the latest minor version will be the supported one.

## Reporting a vulnerability

Please don't open a public issue for a security vulnerability. Instead, report it privately via [GitHub Security Advisories](https://github.com/ashishgituser/springboot-mcp-gateway/security/advisories/new).

Include what you'd include in any good bug report: the affected version/commit, a description of the issue, and reproduction steps if you have them. Expect an initial response within a few days.

## Scope

The gateway delegates authentication entirely to [mcp-security](https://github.com/spring-ai-community/mcp-security); vulnerabilities in that library should be reported there. Issues in how this project uses it — policy bypass, rate-limit bypass, or a caller reaching a tool it shouldn't — are in scope here.
