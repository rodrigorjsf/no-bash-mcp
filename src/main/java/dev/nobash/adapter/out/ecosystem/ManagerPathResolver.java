package dev.nobash.adapter.out.ecosystem;

/**
 * The injectable PATH-resolution seam (AC5) — SHARED cross-ecosystem infrastructure. Given a
 * manager name (e.g. {@code mvn}, {@code go}), reports whether a <em>trusted system</em>
 * executable of that name resolves on the configured {@code PATH}. It searches PATH directories
 * only — NEVER the working directory or a repo wrapper such as {@code ./mvnw} (ADR-0008).
 *
 * <p>It lives in the NEUTRAL {@code adapter.out.ecosystem} parent package (not under any one
 * ecosystem sub-package) precisely because more than one ecosystem needs it: the Maven adapter
 * resolves {@code mvn}, the Go adapter resolves {@code go}. Placing it under {@code …ecosystem.maven}
 * would make the Go adapter depend on the Maven <em>slice</em>, violating the ArchUnit
 * {@code no_ecosystem_adapter_depends_on_another_ecosystem_adapter} rule (the parent package forms
 * no slice, so depending on it is legitimate for every ecosystem).</p>
 *
 * <p>The seam exists because the real test-JVM {@code PATH} cannot be mutated; a unit injects a
 * resolver over a controlled PATH to exercise both the present and absent branches.</p>
 */
@FunctionalInterface
public interface ManagerPathResolver {

    /**
     * @param manager the trusted system manager name (e.g. {@code mvn}, {@code go})
     * @return {@code true} iff an executable of that name is found on the configured PATH
     */
    boolean resolvesOnPath(String manager);
}
