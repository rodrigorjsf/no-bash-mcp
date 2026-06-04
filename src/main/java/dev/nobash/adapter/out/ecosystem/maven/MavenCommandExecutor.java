package dev.nobash.adapter.out.ecosystem.maven;

import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The Maven ecosystem adapter (outbound). Satisfies {@link CommandExecutorPort} for the JVM/
 * Maven ecosystem. It (a) reports whether the trusted system {@code mvn} is installed on PATH
 * via the injected {@link ManagerPathResolver} seam, and (b) launches a command by spawning the
 * explicit {@code argv} array directly.
 *
 * <p>Execution launches {@code new ProcessBuilder(spec.argv())} — the argv array is passed
 * verbatim, NEVER through {@code /bin/sh -c} and never re-split, so shell metacharacters in any
 * token are inert (security-model.md). {@code argv[0]} is the trusted system {@code mvn} name
 * resolved on {@code PATH} by the OS — never a repo wrapper ({@code ./mvnw}), per ADR-0008. The
 * launcher stays outside the agent's control.</p>
 *
 * <p>{@code timedOut} is always {@code false} here — timeout enforcement is a later slice
 * (issue #6). stdout and stderr are drained concurrently (stdout on a worker, stderr on the
 * calling thread) so the classic pipe-buffer deadlock cannot occur on a chatty build.</p>
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

    @Override
    public ExecResult execute(ExecSpec spec) {
        ProcessBuilder pb = toProcessBuilder(spec);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Process process = pb.start();
            // Drain stdout on a worker while stderr drains on this thread — both pipes are read
            // continuously, so a large build output cannot block the child on a full buffer.
            Future<String> stdoutFuture = pool.submit(() -> drain(process.getInputStream()));
            String stderr = drain(process.getErrorStream());
            String stdout = stdoutFuture.get();
            int exitCode = process.waitFor();
            // timedOut is define-and-read only this slice — enforcement is issue #6.
            return new ExecResult(exitCode, stdout, stderr, false);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to launch '" + MANAGER + "' command", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting the '" + MANAGER + "' command", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to capture the '" + MANAGER + "' command output", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Build the {@link ProcessBuilder} for a spec. The command is EXACTLY {@code spec.argv()}
     * (no shell wrapping); the working directory is set when present. Package-visible so a unit
     * can assert the launched command shape (AC9) without spawning a process.
     */
    ProcessBuilder toProcessBuilder(ExecSpec spec) {
        ProcessBuilder pb = new ProcessBuilder(spec.argv());
        if (spec.workingDir() != null && !spec.workingDir().isBlank()) {
            pb.directory(Path.of(spec.workingDir()).toFile());
        }
        return pb;
    }

    private static String drain(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read process output", e);
        }
    }
}
