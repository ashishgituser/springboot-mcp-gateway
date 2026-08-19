package io.github.ashishgituser.mcpgateway.autoconfigure;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;

/**
 * Fallback resolver for applications without Spring Security on the classpath: it reads whatever
 * the servlet container authenticated. Roles are not available this way, so rules matching on roles
 * never match — match on principal names instead.
 */
public class ServletPrincipalResolver implements PrincipalResolver {

  @Override
  public GatewayPrincipal resolve(HttpServletRequest request) {
    Principal principal = request.getUserPrincipal();
    if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
      return GatewayPrincipal.ANONYMOUS;
    }
    return new GatewayPrincipal(principal.getName());
  }
}
