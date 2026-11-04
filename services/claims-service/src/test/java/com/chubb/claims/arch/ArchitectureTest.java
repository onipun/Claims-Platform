package com.chubb.claims.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Boundaries from plan 9 / Task 9. A violation is fixed in the code, never frozen. */
@AnalyzeClasses(packages = "com.chubb.claims", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule controllersDoNotTouchRepositories = noClasses()
      .that().haveSimpleNameContaining("Controller")
      .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
      .orShould().dependOnClassesThat().areAssignableTo(org.springframework.data.repository.Repository.class)
      .because("controllers call services; only services and mappers read the database");

  @ArchTest
  static final ArchRule domainIsFrameworkFree = noClasses()
      .that().resideInAPackage("com.chubb.claims.claim.domain..")
      .should().dependOnClassesThat().resideInAnyPackage(
          "org.springframework..", "jakarta.persistence..", "org.hibernate..", "jakarta.validation..",
          "tools.jackson..", "com.fasterxml..")
      .because("the rules are plain Java so they can be tested exhaustively and reused");

  /** identity's API is the caller type that services receive; its filters, token and key classes are internal. */
  @ArchTest
  static final ArchRule identityIsEnteredOnlyThroughItsApi = classes()
      .that().resideInAPackage("com.chubb.claims.identity..")
      .and().areNotAssignableFrom(com.chubb.claims.identity.AuthenticatedUser.class)
      .should().onlyBeAccessed().byClassesThat().resideInAPackage("com.chubb.claims.identity..")
      .because("everything else sees the caller as AuthenticatedUser and nothing more");

  @ArchTest
  static final ArchRule servicesDoNotUseControllers = noClasses()
      .that().resideOutsideOfPackages("com.chubb.claims.claim.api..", "com.chubb.claims.document..",
          "com.chubb.claims.identity..", "com.chubb.claims.web..")
      .or().haveSimpleNameEndingWith("Service").or().haveSimpleNameEndingWith("Tx")
      .or().haveSimpleNameEndingWith("Mapper")
      .should().dependOnClassesThat().haveSimpleNameContaining("Controller")
      .because("dependencies point inwards: controller -> service -> domain");

  /**
   * Feature packages are the top-level packages under com.chubb.claims. {@code claim.domain} is the
   * framework-free rules kernel that identity and reference also speak (Role, Actor, Line); it is its own
   * slice, below every feature, so "claim uses identity, identity uses Role" is not a cycle. The kernel's
   * own purity is checked by domainIsFrameworkFree and kernelDependsOnNothing.
   */
  @ArchTest
  static final ArchRule noCyclesBetweenFeaturePackages = slices()
      .assignedFrom(new com.tngtech.archunit.library.dependencies.SliceAssignment() {
        @Override
        public String getDescription() { return "feature package"; }

        @Override
        public com.tngtech.archunit.library.dependencies.SliceIdentifier getIdentifierOf(
            com.tngtech.archunit.core.domain.JavaClass c) {
          String pkg = c.getPackageName();
          if (pkg.startsWith("com.chubb.claims.claim.domain")) {
            return com.tngtech.archunit.library.dependencies.SliceIdentifier.of("claim.domain (kernel)");
          }
          if (pkg.length() <= "com.chubb.claims".length()) return com.tngtech.archunit.library.dependencies.SliceIdentifier.ignore();
          String rest = pkg.substring("com.chubb.claims.".length());
          int dot = rest.indexOf('.');
          return com.tngtech.archunit.library.dependencies.SliceIdentifier.of(dot < 0 ? rest : rest.substring(0, dot));
        }
      })
      .should().beFreeOfCycles();

  @ArchTest
  static final ArchRule kernelDependsOnNothing = noClasses()
      .that().resideInAPackage("com.chubb.claims.claim.domain..")
      .should().dependOnClassesThat().resideInAnyPackage(
          "com.chubb.claims.claim", "com.chubb.claims.claim.api..", "com.chubb.claims.identity..",
          "com.chubb.claims.reference..", "com.chubb.claims.document..", "com.chubb.claims.web..",
          "com.chubb.claims.outbox..", "com.chubb.claims.config..", "com.chubb.claims.demo..");
}
