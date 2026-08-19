package io.github.ashishgituser.mcpgateway.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleBasedPolicyEngineTest {

  private static final GatewayPrincipal ADMIN = new GatewayPrincipal("alice", Set.of("admin"));
  private static final GatewayPrincipal VIEWER = new GatewayPrincipal("bob", Set.of("viewer"));

  @Test
  void fallsBackToTheDefaultEffectWhenNoRuleMatches() {
    RuleBasedPolicyEngine engine = new RuleBasedPolicyEngine(List.of(), PolicyEffect.DENY);

    assertThat(engine.evaluate(ADMIN, "fs__readFile").allowed()).isFalse();
  }

  @Test
  void aRoleBasedAllowRuleGrantsMatchingCallers() {
    PolicyRule allowAdmins =
        new PolicyRule(PolicyEffect.ALLOW, Set.of(), Set.of("admin"), List.of(ToolPattern.of("*")));
    RuleBasedPolicyEngine engine =
        new RuleBasedPolicyEngine(List.of(allowAdmins), PolicyEffect.DENY);

    assertThat(engine.evaluate(ADMIN, "fs__deleteFile").allowed()).isTrue();
    assertThat(engine.evaluate(VIEWER, "fs__deleteFile").allowed()).isFalse();
  }

  @Test
  void earlierRulesTakePrecedenceSoANarrowDenyCanOverrideABroadAllow() {
    PolicyRule allowAllFilesystem =
        new PolicyRule(PolicyEffect.ALLOW, Set.of(), Set.of(), List.of(ToolPattern.of("fs__*")));
    PolicyRule denyDelete =
        new PolicyRule(
            PolicyEffect.DENY, Set.of(), Set.of(), List.of(ToolPattern.of("fs__delete*")));

    RuleBasedPolicyEngine denyFirst =
        new RuleBasedPolicyEngine(List.of(denyDelete, allowAllFilesystem), PolicyEffect.DENY);
    assertThat(denyFirst.evaluate(VIEWER, "fs__deleteFile").allowed()).isFalse();
    assertThat(denyFirst.evaluate(VIEWER, "fs__readFile").allowed()).isTrue();

    RuleBasedPolicyEngine allowFirst =
        new RuleBasedPolicyEngine(List.of(allowAllFilesystem, denyDelete), PolicyEffect.DENY);
    assertThat(allowFirst.evaluate(VIEWER, "fs__deleteFile").allowed()).isTrue();
  }

  @Test
  void principalConstraintMatchesOnlyTheNamedCaller() {
    PolicyRule allowOnlyAlice =
        new PolicyRule(PolicyEffect.ALLOW, Set.of("alice"), Set.of(), List.of(ToolPattern.of("*")));
    RuleBasedPolicyEngine engine =
        new RuleBasedPolicyEngine(List.of(allowOnlyAlice), PolicyEffect.DENY);

    assertThat(engine.evaluate(ADMIN, "fs__readFile").allowed()).isTrue();
    assertThat(engine.evaluate(VIEWER, "fs__readFile").allowed()).isFalse();
  }
}
