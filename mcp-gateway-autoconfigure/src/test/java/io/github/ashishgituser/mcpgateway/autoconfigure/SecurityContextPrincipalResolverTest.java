package io.github.ashishgituser.mcpgateway.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityContextPrincipalResolverTest {

  private final SecurityContextPrincipalResolver resolver = new SecurityContextPrincipalResolver();

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void resolvesTheAuthenticatedPrincipalAndItsAuthoritiesAsRoles() {
    var authentication =
        new TestingAuthenticationToken(
            "alice", "n/a", java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    authentication.setAuthenticated(true);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    GatewayPrincipal principal = resolver.resolve((HttpServletRequest) null);

    assertThat(principal.name()).isEqualTo("alice");
    assertThat(principal.roles()).containsExactly("ROLE_ADMIN");
  }

  @Test
  void fallsBackToAnonymousWhenThereIsNoAuthenticatedPrincipal() {
    assertThat(resolver.resolve((HttpServletRequest) null)).isEqualTo(GatewayPrincipal.ANONYMOUS);
  }
}
