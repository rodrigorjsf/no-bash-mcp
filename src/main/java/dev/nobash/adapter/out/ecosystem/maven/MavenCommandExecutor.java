package dev.nobash.adapter.out.ecosystem.maven;

import dev.nobash.domain.port.out.CommandExecutorPort;
import jakarta.inject.Singleton;

/**
 * The Maven ecosystem adapter (outbound). Satisfies {@link CommandExecutorPort} for the JVM/
 * Maven ecosystem. This slice implements only manager resolution: it reports whether the
 * trusted system {@code mvn} is installed on PATH (AC5), via the injected
 * {@link ManagerPathResolver} seam. Real process launch is a later slice; this adapter never
 * spawns a process here.
 *
 * <p>It resolves the trusted system binary only — never a repo wrapper ({@code ./mvnw}),
 * per ADR-0008. The launcher stays outside the agent's control.</p>
 */
@Singleton
public class MavenCommandExecutor implements CommandExecutorPort {

    private static final String MANAGER = "mvn";

    private final ManagerPathResolver resolver;

    public MavenCommandExecutor(ManagerPathResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean isManagerInstalled() {
        return resolver.resolvesOnPath(MANAGER);
    }
}
