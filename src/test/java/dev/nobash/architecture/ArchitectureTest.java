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
    void the_result_domain_is_io_free() {
        // AC7 (issue #3) — the pure Surefire normalizer must do NO directory read, NO file
        // read, and launch NO process; report content arrives already in memory. ArchUnit's
        // layer rules do not catch java.io.File / java.nio.file / Process leaking into the
        // domain, so this rule makes I/O-freedom VERIFIABLE rather than self-asserted. The
        // normalizer parses via ByteArrayInputStream (javax.xml.parsers + org.w3c.dom), which
        // this rule permits; File/Path/Files/Process/ProcessBuilder are forbidden.
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE + ".domain.result..")
                .should().dependOnClassesThat().resideInAnyPackage("java.nio.file..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.File")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Process")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    void the_git_domain_is_io_free() {
        // PRD-002 (issue #24) — the pure git-status parser must do NO directory read, NO file
        // read, and launch NO process; porcelain output arrives already in memory (exactly like
        // the result-domain rule). This makes the porcelain-parse keystone VERIFIABLE: the parser
        // consumes git's machine-format stdout, never scrapes the filesystem or spawns git itself.
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE + ".domain.git..")
                .should().dependOnClassesThat().resideInAnyPackage("java.nio.file..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.File")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Process")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    void the_run_tests_use_case_depends_on_the_ecosystem_abstraction_not_a_concrete_adapter() {
        // ADR-0011 — RunTestsUseCase is ecosystem-agnostic: it delegates ecosystem-specific
        // behaviour to the EcosystemAdapter abstraction (in application.verb.tests) and must NEVER
        // reach a concrete adapter in adapter.out.ecosystem.. (e.g. MavenEcosystemAdapter). DI wires
        // the concrete bean in by interface; a use-case → concrete-adapter edge would couple the
        // invariant floor to one ecosystem and is forbidden. With one adapter today this still holds
        // (the use-case names only the interface) — tolerated empty by design.
        ArchRule rule = noClasses()
                .that().resideInAPackage(BASE + ".application.verb.tests..")
                .should().dependOnClassesThat().resideInAPackage(BASE + ".adapter.out.ecosystem..")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    void no_ecosystem_adapter_depends_on_another_ecosystem_adapter() {
        // ADR-0011 — each ecosystem adapter lives under adapter.out.ecosystem.<ecosystem>. No
        // adapter may reach a SIBLING adapter: the floor is single-source in the use-case, so an
        // adapter never needs another ecosystem's code. slices().notDependOnEachOther() groups by
        // the captured ecosystem token and flags only cross-ecosystem edges (intra-ecosystem
        // dependencies are legitimate). With one ecosystem today it matches nothing — tolerated by
        // design, ready the moment Node/Go adapters land.
        ArchRule rule = slices()
                .matching(BASE + ".adapter.out.ecosystem.(*)..")
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
