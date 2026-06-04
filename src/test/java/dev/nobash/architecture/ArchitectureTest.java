package dev.nobash.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * AC8 — ArchUnit core rules guard the hexagonal dependency rule from this slice on, and they
 * tolerate the PARTIAL tree (one verb slice, empty layers). The rules are plain {@code @Test}
 * using {@link ClassFileImporter} (the {@code archunit} core artifact, NOT {@code archunit-junit5}
 * — incompatible with JUnit 6, issue #1556). Empty-should tolerance comes from
 * {@code src/test/resources/archunit.properties} ({@code archunit.fail.on.empty.should=false}),
 * belt-and-suspendered with {@code allowEmptyShould(true)} on each rule.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ArchitectureTest {

    private static final String BASE = "dev.nobash";
    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    void the_pure_domain_never_depends_on_an_adapter() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE + ".domain..")
                .should().dependOnClassesThat().resideInAPackage(BASE + ".adapter..")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    void the_pure_domain_never_depends_on_the_application_layer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE + ".domain..")
                .should().dependOnClassesThat().resideInAPackage(BASE + ".application..")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    void no_verb_slice_depends_on_another_verb_slice() {
        // Each verb slice lives under application.verb.<slice>. No slice may reach a SIBLING
        // slice (intra-slice dependencies are legitimate, so a plain noClasses(verb..).should
        // (verb..) would wrongly flag them — and Micronaut's generated $Definition beans).
        // slices().notDependOnEachOther() groups by the captured slice token and only flags
        // cross-slice edges. With one slice today it matches nothing — tolerated by design.
        ArchRule rule = slices()
                .matching(BASE + ".application.verb.(*)..")
                .should().notDependOnEachOther()
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    void the_layered_dependency_rule_holds() {
        // Only layers that have classes in THIS slice are declared (no empty infra/config
        // layers pre-declared). domain is the innermost; adapter the outermost.
        ArchRule rule = layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy(BASE + ".domain..")
                .layer("Application").definedBy(BASE + ".application..")
                .layer("Adapter").definedBy(BASE + ".adapter..")
                .whereLayer("Adapter").mayNotBeAccessedByAnyLayer()
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapter")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapter")
                .withOptionalLayers(true);

        rule.check(productionClasses);
    }
}
