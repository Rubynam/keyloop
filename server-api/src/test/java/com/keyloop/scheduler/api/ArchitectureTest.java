package com.keyloop.scheduler.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import javax.sql.DataSource;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/**
 * CLAUDE.md §1 rules as tests, on main classes only (Spec05 §7 and tracing spine AD-10,
 * ARC-01…10). A change that breaks the layering fails the build instead of waiting for a review.
 *
 * <pre>
 * Controller (controller) ──► Service ──► Persistence (repository, cache, store)
 *      x── a controller that imports a *Impl or JdbcClient fails ARC-02 / ARC-03
 * </pre>
 */
@AnalyzeClasses(packages = ArchitectureTest.BASE, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String BASE = "com.keyloop.scheduler.api";

    /** ARC-01: controllers call services, services call persistence, never the other way. */
    @ArchTest
    static final ArchRule layers = layeredArchitecture()
            .consideringAllDependencies()
            .layer("Controller").definedBy(BASE + ".controller..")
            .layer("Service").definedBy(BASE + ".service..")
            .layer("Persistence").definedBy(BASE + ".repository..",
                    BASE + ".cache..",
                    BASE + ".store..")
            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
            .whereLayer("Persistence").mayOnlyBeAccessedByLayers("Service");

    /** ARC-02: callers see interfaces only; Spring wiring in config may name an implementation. */
    @ArchTest
    static final ArchRule implementationsAreHidden = noClasses()
            .that().resideOutsideOfPackages("..impl..", "..config..")
            .should().dependOnClassesThat().resideInAPackage(BASE + "..impl..");

    /** ARC-03: only repository / cache / store implementations (and config) touch JDBC, Redis or the DataSource. */
    @ArchTest
    static final ArchRule dataAccessOnlyInImplementations = noClasses()
            .that().resideOutsideOfPackages("..repository..impl..", "..cache..impl..", "..store..impl..", "..config..")
            .should().dependOnClassesThat().areAssignableTo(JdbcClient.class)
            .orShould().dependOnClassesThat().areAssignableTo(JdbcOperations.class)
            .orShould().dependOnClassesThat().areAssignableTo(NamedParameterJdbcOperations.class)
            .orShould().dependOnClassesThat().areAssignableTo(RedisOperations.class)
            .orShould().dependOnClassesThat().areAssignableTo(RedisConnectionFactory.class)
            .orShould().dependOnClassesThat().areAssignableTo(DataSource.class);

    /** ARC-04: stereotypes sit on implementations in their layer. */
    @ArchTest
    static final ArchRule servicesInServiceImpl = classes()
            .that().areAnnotatedWith(Service.class)
            .should().resideInAPackage("..service..impl..");

    @ArchTest
    static final ArchRule repositoriesInRepositoryImpl = classes()
            .that().areAnnotatedWith(Repository.class)
            .should().resideInAPackage("..repository..impl..");

    @ArchTest
    static final ArchRule restControllersInController = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage("..controller..");

    /** ARC-05: a feature package of a port layer holds interfaces only; classes go to impl/. */
    @ArchTest
    static final ArchRule portsAreInterfaces = classes()
            .that().resideInAnyPackage("..service.*", "..repository.*", "..cache.*", "..store.*",
                    // BASE-anchored: "..tracing.*" would also match config.tracing (classes, by design)
                    BASE + ".tracing.*")
            .and().areTopLevelClasses()
            .should().beInterfaces();

    /** ARC-06: constructor injection only. */
    @ArchTest
    static final ArchRule noFieldInjection = noFields()
            .should().beAnnotatedWith(Autowired.class);

    /** ARC-06: no setter or method injection either. */
    @ArchTest
    static final ArchRule noMethodInjection = noMethods()
            .should().beAnnotatedWith(Autowired.class);

    /** ARC-07: features (2nd segment under the module, any layer) do not depend on each other in a cycle. */
    @ArchTest
    static final ArchRule featuresAreFreeOfCycles = slices()
            .matching(BASE + ".*.(*)..")
            .should().beFreeOfCycles();

    /** ARC-08: every class sits in a known layer package, or is the application class. */
    @ArchTest
    static final ArchRule knownLayerPackagesOnly = classes()
            .that().doNotHaveSimpleName("SchedulerApiApplication")
            .should().resideInAnyPackage(
                    BASE + ".controller..",
                    BASE + ".service..",
                    BASE + ".repository..",
                    BASE + ".cache..",
                    BASE + ".store..",
                    BASE + ".model..",
                    BASE + ".dto..",
                    BASE + ".exception..",
                    BASE + ".constant..",
                    BASE + ".config..",
                    BASE + ".web..",
                    BASE + ".schedule..",
                    BASE + ".job..",
                    BASE + ".tracing..");

    /**
     * ARC-09: Micrometer tracing / observation types only in tracing implementations, config and web/TraceIds.
     * Everything else gets spans from framework instrumentation or through a tracing port.
     */
    @ArchTest
    static final ArchRule tracingApiOnlyInTracingImplementations = noClasses()
            .that().resideOutsideOfPackages("..tracing..impl..", "..config..")
            .and().doNotHaveFullyQualifiedName(BASE + ".web.TraceIds")
            // Pre-dates the tracing spine: fail-open adds an event to the current span (deferred-work.md)
            .and().doNotHaveFullyQualifiedName(BASE + ".cache.availability.impl.RedisAvailabilityCache")
            .should().dependOnClassesThat().resideInAnyPackage("io.micrometer.tracing..", "io.micrometer.observation..");

    /** ARC-10: tracing ports are called by service implementations only (never a controller or repository). */
    @ArchTest
    static final ArchRule tracingPortsOnlyFromServiceImplementations = noClasses()
            .that().resideOutsideOfPackages("..service..impl..", BASE + ".tracing..")
            .should().dependOnClassesThat().resideInAPackage(BASE + ".tracing..");
}
