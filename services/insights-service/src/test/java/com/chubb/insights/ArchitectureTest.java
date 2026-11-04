package com.chubb.insights;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.chubb.insights", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule controllersDoNotTouchJdbc = noClasses()
      .that().haveSimpleNameContaining("Controller")
      .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "javax.sql..", "java.sql..",
          "org.springframework.data..", "jakarta.persistence..", "com.chubb.insights.query..",
          "com.chubb.insights.projection..")
      .because("controllers call the service; only the query and projection classes read or write the database");

  @ArchTest
  static final ArchRule insightsDoesNotImportClaims = noClasses()
      .that().resideInAPackage("com.chubb.insights..")
      .should().dependOnClassesThat().resideInAPackage("com.chubb.claims..")
      .because("no shared library: the event contract is the only coupling (D10)");
}
