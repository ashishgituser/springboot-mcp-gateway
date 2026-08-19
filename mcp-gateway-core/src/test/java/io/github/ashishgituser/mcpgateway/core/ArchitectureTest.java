package io.github.ashishgituser.mcpgateway.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Locks in the two architectural guarantees the rest of the codebase (and the README) depend on:
 * this module stays transport/framework agnostic, and its SPIs don't reach back into the router
 * that consumes them.
 */
class ArchitectureTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter().importPackages("io.github.ashishgituser.mcpgateway.core");

  @Test
  void staysFreeOfAnySpringDependency() {
    ArchRule rule =
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.servlet..");
    rule.check(CLASSES);
  }

  @Test
  void policyRateLimitAndObservabilityDoNotDependOnRouting() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAnyPackage(
                "io.github.ashishgituser.mcpgateway.core.policy..",
                "io.github.ashishgituser.mcpgateway.core.ratelimit..",
                "io.github.ashishgituser.mcpgateway.core.observability..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("io.github.ashishgituser.mcpgateway.core.routing..");
    rule.check(CLASSES);
  }
}
