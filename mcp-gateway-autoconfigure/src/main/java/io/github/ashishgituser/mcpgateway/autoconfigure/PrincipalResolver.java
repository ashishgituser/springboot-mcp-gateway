package io.github.ashishgituser.mcpgateway.autoconfigure;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Turns an authenticated HTTP request into the principal that policy is evaluated against. Declare
 * your own bean to plug in a different identity source (an API-key store, a custom claim, ...).
 */
@FunctionalInterface
public interface PrincipalResolver {

  GatewayPrincipal resolve(HttpServletRequest request);
}
