package dev.nobash.adapter.out.ecosystem;

import jakarta.inject.Singleton;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves a trusted system manager by scanning the configured {@code PATH} for an executable
 * of that name (AC5, ADR-0008) — SHARED cross-ecosystem infrastructure (resolves {@code mvn} for
 * Maven, {@code go} for Go). It searches PATH directories only — it never inspects the working
 * directory and never resolves a repo wrapper ({@code ./mvnw}), so the launcher stays outside the
 * agent's control.
 *
 * <p>It lives in the NEUTRAL {@code adapter.out.ecosystem} parent package (not under any one
 * ecosystem sub-package): more than one ecosystem injects it, and the parent package forms no
 * ArchUnit ecosystem slice, so every adapter may depend on it without a cross-slice violation.</p>
 *
 * <p>On Windows the launcher is a {@code .cmd}/{@code .bat} shim (gotcha G13); the resolver
 * checks the bare name plus those extensions. On POSIX it requires the file to be executable.</p>
 *
 * <p>The no-arg constructor binds to the live {@code PATH} for production DI; the second
 * constructor takes an explicit PATH string for unit testing over a controlled environment.</p>
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

    /**
     * @param pathEnv an explicit {@code PATH}-style string to scan (public so a unit in any package
     *                can exercise the present/absent branches over a controlled environment)
     */
    public PathScanningManagerResolver(String pathEnv) {
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
