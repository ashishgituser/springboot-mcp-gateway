package io.github.ashishgituser.mcpgateway.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolVisibilityTest {

  @Test
  void unrestrictedShowsEverythingToEveryCaller() {
    ToolVisibility visibility = ToolVisibility.unrestricted();

    assertThat(visibility.isVisible(new GatewayPrincipal("analyst"), "database__delete")).isTrue();
    assertThat(visibility.isVisible(GatewayPrincipal.ANONYMOUS, "file:///etc/secrets")).isTrue();
  }

  @Test
  void governedByHidesWhatThePolicyWouldDeny() {
    ToolVisibility visibility = ToolVisibility.governedBy(analystCanOnlyQuery());
    GatewayPrincipal alice = new GatewayPrincipal("alice", Set.of("analyst"));

    assertThat(visibility.isVisible(alice, "database__query")).isTrue();
    assertThat(visibility.isVisible(alice, "database__delete")).isFalse();
  }

  @Test
  void twoCallersSeeDifferentSlicesOfTheSameCatalog() {
    ToolVisibility visibility = ToolVisibility.governedBy(analystCanOnlyQuery());

    GatewayPrincipal admin = new GatewayPrincipal("root", Set.of("admin"));
    GatewayPrincipal analyst = new GatewayPrincipal("alice", Set.of("analyst"));

    assertThat(visibility.isVisible(admin, "database__delete")).isTrue();
    assertThat(visibility.isVisible(analyst, "database__delete")).isFalse();
    assertThat(visibility.isVisible(GatewayPrincipal.ANONYMOUS, "database__query")).isFalse();
  }

  @Test
  void filtersPromptNamesAndResourceUrisTheSameWayAsTools() {
    // Prompts and resources go through the same engine, so a rule cannot be sidestepped by asking
    // for the same thing under a different MCP primitive.
    PolicyEngine engine =
        new RuleBasedPolicyEngine(
            List.of(
                new PolicyRule(
                    PolicyEffect.DENY,
                    Set.of(),
                    Set.of(),
                    List.of(ToolPattern.of("file:///etc/*"), ToolPattern.of("*__internalReview")))),
            PolicyEffect.ALLOW);
    ToolVisibility visibility = ToolVisibility.governedBy(engine);
    GatewayPrincipal caller = new GatewayPrincipal("alice");

    assertThat(visibility.isVisible(caller, "file:///etc/shadow")).isFalse();
    assertThat(visibility.isVisible(caller, "github__internalReview")).isFalse();
    assertThat(visibility.isVisible(caller, "file:///var/log/app.log")).isTrue();
  }

  private static PolicyEngine analystCanOnlyQuery() {
    return new RuleBasedPolicyEngine(
        List.of(
            new PolicyRule(
                PolicyEffect.ALLOW, Set.of(), Set.of("admin"), List.of(ToolPattern.of("*"))),
            new PolicyRule(
                PolicyEffect.ALLOW,
                Set.of(),
                Set.of("analyst"),
                List.of(ToolPattern.of("database__query")))),
        PolicyEffect.DENY);
  }
}
