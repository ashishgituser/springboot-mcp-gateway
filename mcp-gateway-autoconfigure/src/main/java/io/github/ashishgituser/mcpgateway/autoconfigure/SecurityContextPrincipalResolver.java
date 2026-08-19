package io.github.ashishgituser.mcpgateway.autoconfigure;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Reads the caller and their granted authorities out of Spring Security's context, which {@code
 * mcp-server-security} (or the application's own security filter chain) populates before the MCP
 * servlet runs. Used automatically once Spring Security is on the classpath, so role-based policy
 * rules work out of the box for anyone wiring auth through {@code mcp-security}.
 */
public class SecurityContextPrincipalResolver implements PrincipalResolver {

  @Override
  public GatewayPrincipal resolve(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication.getName() == null) {
      return GatewayPrincipal.ANONYMOUS;
    }
    Set<String> roles =
        authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .collect(Collectors.toSet());
    return new GatewayPrincipal(authentication.getName(), roles);
  }
}
