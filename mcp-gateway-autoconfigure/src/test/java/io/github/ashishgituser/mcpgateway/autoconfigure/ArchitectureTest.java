package io.github.ashishgituser.mcpgateway.autoconfigure;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Every bean here is wired through constructors or {@code @Bean} factory methods, never field
 * injection, and every {@code @AutoConfiguration} class lives directly in this package so it's easy
 * to find and to list in {@code AutoConfiguration.imports}.
 */
class ArchitectureTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter().importPackages("io.github.ashishgituser.mcpgateway.autoconfigure");

  @Test
  void doesNotUseFieldInjection() {
    ArchRule rule = noFields().should().beAnnotatedWith(Autowired.class);
    rule.check(CLASSES);
  }

  @Test
  void autoConfigurationClassesLiveInTheRootPackage() {
    ArchRule rule =
        classes()
            .that()
            .areAnnotatedWith(AutoConfiguration.class)
            .should()
            .resideInAPackage("io.github.ashishgituser.mcpgateway.autoconfigure");
    rule.check(CLASSES);
  }
}
