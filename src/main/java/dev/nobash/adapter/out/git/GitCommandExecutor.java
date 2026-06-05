package dev.nobash.adapter.out.git;

import dev.nobash.adapter.out.ecosystem.maven.ManagerPathResolver;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The git ecosystem adapter (outbound) — the FOUNDATIONAL git seam (PRD-002, issue #24).
 * Satisfies {@link CommandExecutorPort} for the trusted system {@code git}. It (a) reports
 * whether {@code git} is installed on PATH via the shared {@link ManagerPathResolver} seam
 * (reused from the Maven adapter — same Adapter layer, ArchUnit-clean), and (b) launches a
 * command by spawning the explicit {@code argv} array directly.
 *
 * <p><b>DI qualifier (PRD-002).</b> There are now two {@link CommandExecutorPort} beans. The
 * Maven adapter is {@code @Primary} (resolves bare injections), so this git adapter carries the
 * {@code @Named("git")} qualifier; the git use-case injects it explicitly via
 * {@code @Named("git")}. This keeps {@code RunTestsUseCase} / {@code RunBuildUseCase} resolving
 * Maven with ZERO edits.</p>
 *
 * <p>Execution mirrors {@code MavenCommandExecutor}: {@code new ProcessBuilder(spec.argv())} —
 * the argv array passed verbatim, NEVER through {@code /bin/sh -c} and never re-split, so shell
 * metacharacters in any token are inert (security-model.md). {@code argv[0]} is the trusted
 * system {@code git} name resolved on {@code PATH} by the OS — never a repo wrapper (ADR-0008).
 * Both pipes drain on worker threads and the calling thread blocks only on the bounded
 * {@code waitFor}, so the deadline is always honoured (issue #6).</p>
 */
@Singleton
@Named("git")
public class GitCommandExecutor implements CommandExecutorPort {

    private static final String MANAGER = "git";

    /** Bounded grace to collect drained partial output AFTER the tree is killed (mirror Maven). */
    private static final long DRAIN_GRACE_SECONDS = 5;

    private final ManagerPathResolver resolver;

    public GitCommandExecutor(ManagerPathResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean isManagerInstalled() {
        return resolver.resolvesOnPath(MANAGER);
    }

    @Override
    public ExecResult execute(ExecSpec spec) {
        ProcessBuilder pb = toProcessBuilder(spec);
        // BOTH pipes drain on workers so the calling thread blocks ONLY on the bounded waitFor.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Process process = pb.start();
            Future<String> stdoutFuture = pool.submit(() -> drain(process.getInputStream()));
            Future<String> stderrFuture = pool.submit(() -> drain(process.getErrorStream()));

            boolean exited = process.waitFor(spec.timeoutSeconds(), TimeUnit.SECONDS);
            if (!exited) {
                String partialStdout = killTreeAndDrain(process, stdoutFuture);
                String partialStderr = drainQuietly(stderrFuture);
                return new ExecResult(-1, partialStdout, partialStderr, true);
            }

            String stdout = stdoutFuture.get();
            String stderr = stderrFuture.get();
            int exitCode = process.exitValue();
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
     * Kill the process tree on a timeout and collect the stdout that drains once it is reaped.
     * Snapshots descendants BEFORE destroying the parent (a dead parent's children reparent to
     * init and are no longer enumerable), then forcibly destroys the parent and each descendant.
     */
    private static String killTreeAndDrain(Process process, Future<String> stdoutFuture) {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        for (ProcessHandle child : descendants) {
            child.destroyForcibly();
        }
        return drainQuietly(stdoutFuture);
    }

    /** Collect a drain future's captured output under a bounded grace, never re-hanging. */
    private static String drainQuietly(Future<String> future) {
        try {
            return future.get(DRAIN_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException e) {
            return "";
        }
    }

    /**
     * Build the {@link ProcessBuilder} for a spec. The command is EXACTLY {@code spec.argv()}
     * (no shell wrapping); the working directory is set when present. Package-visible so a unit
     * can assert the launched command shape without spawning a process.
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
