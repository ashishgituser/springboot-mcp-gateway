package io.github.ashishgituser.mcpgateway.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.ashishgituser.mcpgateway.core.policy.GatewayPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServletPrincipalResolverTest {

  private final ServletPrincipalResolver resolver = new ServletPrincipalResolver();

  @Mock private HttpServletRequest request;

  @Test
  void resolvesTheServletContainerPrincipalByName() {
    Principal containerPrincipal = () -> "alice";
    when(request.getUserPrincipal()).thenReturn(containerPrincipal);

    assertThat(resolver.resolve(request)).isEqualTo(new GatewayPrincipal("alice"));
  }

  @Test
  void fallsBackToAnonymousWhenTheRequestIsUnauthenticated() {
    when(request.getUserPrincipal()).thenReturn(null);

    assertThat(resolver.resolve(request)).isEqualTo(GatewayPrincipal.ANONYMOUS);
  }
}
