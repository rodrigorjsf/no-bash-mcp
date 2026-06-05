package dev.nobash.adapter.out.ecosystem.maven;

import jakarta.inject.Singleton;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves a trusted system manager by scanning the configured {@code PATH} for an executable
 * of that name (AC5, ADR-0008). It searches PATH directories only — it never inspects the
 * working directory and never resolves a repo wrapper ({@code ./mvnw}), so the launcher stays
 * outside the agent's control.
 *
 * <p>On Windows the launcher is a {@code .cmd}/{@code .bat} shim (gotcha G13); the resolver
 * checks the bare name plus those extensions. On POSIX it requires the file to be executable.</p>
 *
 * <p>The no-arg constructor binds to the live {@code PATH} for production DI; the
 * package-visible constructor takes an explicit PATH string for unit testing over a controlled
 * environment.</p>
 */
@Singleton
public class PathScanningManagerResolver implements ManagerPathResolver {

    private static final List<String> WINDOWS_EXECUTABLE_EXTENSIONS = List.of(".cmd", ".bat", ".exe", "");
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private final String pathEnv;

    public PathScanningManagerResolver() {
        this(System.getenv("PATH"));
    }

    PathScanningManagerResolver(String pathEnv) {
        this.pathEnv = pathEnv;
    }

    @Override
    public boolean resolvesOnPath(String manager) {
        if (pathEnv == null || pathEnv.isEmpty()) {
            return false;
        }
        for (String entry : pathEnv.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path dir = Path.of(entry);
            if (IS_WINDOWS) {
                for (String ext : WINDOWS_EXECUTABLE_EXTENSIONS) {
                    if (isExecutableFile(dir.resolve(manager + ext))) {
                        return true;
                    }
                }
            } else if (isExecutableFile(dir.resolve(manager))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExecutableFile(Path candidate) {
        return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }
}
