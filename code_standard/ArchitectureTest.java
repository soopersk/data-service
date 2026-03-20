package com.example.myservice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/**
 * Architectural fitness function tests.
 *
 * <p>These tests enforce layer boundaries, naming conventions, and dependency rules at compile time.
 * If a developer violates the architecture, the build fails with a clear message.
 */
@AnalyzeClasses(
    packages = "com.example.myservice",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  // ━━━ Layer Dependency Rules ━━━

  @ArchTest
  static final ArchRule layerDependencies =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("Controller")
          .definedBy("..controller..")
          .layer("Service")
          .definedBy("..service..")
          .layer("Repository")
          .definedBy("..repository..")
          .layer("Model")
          .definedBy("..model..")
          .whereLayer("Controller")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Service")
          .mayOnlyBeAccessedByLayers("Controller", "Service")
          .whereLayer("Repository")
          .mayOnlyBeAccessedByLayers("Service");

  // ━━━ Naming Conventions ━━━

  @ArchTest
  static final ArchRule controllersShouldEndWithController =
      classes()
          .that()
          .areAnnotatedWith(RestController.class)
          .or()
          .areAnnotatedWith(Controller.class)
          .should()
          .haveSimpleNameEndingWith("Controller");

  @ArchTest
  static final ArchRule servicesShouldEndWithService =
      classes()
          .that()
          .areAnnotatedWith(Service.class)
          .should()
          .haveSimpleNameEndingWith("Service")
          .orShould()
          .haveSimpleNameEndingWith("ServiceImpl");

  @ArchTest
  static final ArchRule repositoriesShouldEndWithRepository =
      classes()
          .that()
          .areAnnotatedWith(Repository.class)
          .should()
          .haveSimpleNameEndingWith("Repository");

  // ━━━ Anti-Pattern Prevention ━━━

  @ArchTest
  static final ArchRule noFieldInjection =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.beans.factory.annotation.Autowired")
          .as("Use constructor injection instead of @Autowired field injection");

  @ArchTest
  static final ArchRule controllersShouldNotCallRepositoriesDirectly =
      noClasses()
          .that()
          .resideInAPackage("..controller..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..repository..");

  @ArchTest
  static final ArchRule servicesShouldNotUseRequestOrResponse =
      noClasses()
          .that()
          .resideInAPackage("..service..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("jakarta.servlet.http.HttpServletRequest")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("jakarta.servlet.http.HttpServletResponse")
          .as("Services should not depend on HTTP layer — use DTOs");
}
