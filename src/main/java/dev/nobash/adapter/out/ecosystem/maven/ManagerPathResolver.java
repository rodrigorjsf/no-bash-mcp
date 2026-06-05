package dev.nobash.adapter.out.ecosystem.maven;

/**
 * The injectable PATH-resolution seam (AC5). Given a manager name (e.g. {@code mvn}), reports
 * whether a <em>trusted system</em> executable of that name resolves on the configured
 * {@code PATH}. It searches PATH directories only — NEVER the working directory or a repo
 * wrapper such as {@code ./mvnw} (ADR-0008).
 *
 * <p>The seam exists because the real test-JVM {@code PATH} cannot be mutated; a unit injects
 * a resolver over a controlled PATH to exercise both the mvn-present and mvn-absent branches.</p>
 */
@FunctionalInterface
public interface ManagerPathResolver {

    /**
     * @param manager the trusted system manager name (e.g. {@code mvn})
     * @return {@code true} iff an executable of that name is found on the configured PATH
     */
    boolean resolvesOnPath(String manager);
}
