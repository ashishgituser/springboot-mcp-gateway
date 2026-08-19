package io.github.ashishgituser.mcpgateway.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolVisibilityTest {

  private static final List<Tool> CATALOG =
      List.of(tool("database__query"), tool("database__delete"), tool("github__createPr"));

  @Test
  void unrestrictedShowsTheWholeCatalog() {
    List<Tool> visible =
        ToolVisibility.unrestricted().visibleTo(new GatewayPrincipal("analyst"), CATALOG);

    assertThat(names(visible))
        .containsExactly("database__query", "database__delete", "github__createPr");
  }

  @Test
  void governedByHidesToolsThePolicyWouldDeny() {
    PolicyEngine engine =
        new RuleBasedPolicyEngine(
            List.of(
                new PolicyRule(
                    PolicyEffect.ALLOW,
                    Set.of(),
                    Set.of("analyst"),
                    List.of(ToolPattern.of("database__query")))),
            PolicyEffect.DENY);

    List<Tool> visible =
        ToolVisibility.governedBy(engine)
            .visibleTo(new GatewayPrincipal("alice", Set.of("analyst")), CATALOG);

    assertThat(names(visible)).containsExactly("database__query");
  }

  @Test
  void twoCallersSeeDifferentSlicesOfTheSameCatalog() {
    PolicyEngine engine =
        new RuleBasedPolicyEngine(
            List.of(
                new PolicyRule(
                    PolicyEffect.ALLOW, Set.of(), Set.of("admin"), List.of(ToolPattern.of("*"))),
                new PolicyRule(
                    PolicyEffect.ALLOW,
                    Set.of(),
                    Set.of("analyst"),
                    List.of(ToolPattern.of("database__query")))),
            PolicyEffect.DENY);
    ToolVisibility visibility = ToolVisibility.governedBy(engine);

    List<Tool> adminView =
        visibility.visibleTo(new GatewayPrincipal("root", Set.of("admin")), CATALOG);
    List<Tool> analystView =
        visibility.visibleTo(new GatewayPrincipal("alice", Set.of("analyst")), CATALOG);
    List<Tool> anonymousView = visibility.visibleTo(GatewayPrincipal.ANONYMOUS, CATALOG);

    assertThat(names(adminView)).hasSize(3);
    assertThat(names(analystView)).containsExactly("database__query");
    assertThat(anonymousView).isEmpty();
  }

  private static List<String> names(List<Tool> tools) {
    return tools.stream().map(Tool::name).toList();
  }

  private static Tool tool(String name) {
    return Tool.builder(name, Map.of("type", "object")).build();
  }
}
