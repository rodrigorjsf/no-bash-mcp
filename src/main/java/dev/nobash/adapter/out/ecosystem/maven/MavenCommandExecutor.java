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
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 * <p>Timeout enforcement (issue #6): the calling thread does a BOUNDED
 * {@code process.waitFor(spec.timeoutSeconds(), SECONDS)}. On expiry it snapshots the live
 * descendants BEFORE killing the parent (once the parent dies its descendants reparent to init
 * and {@code descendants()} no longer finds them — the classic tree-kill bug), {@code
 * destroyForcibly()}-s the parent then every snapshotted descendant, drains whatever partial
 * output the pipes EOF after the tree is reaped, and returns a {@code timedOut=true} result.</p>
 *
 * <p>Both stdout AND stderr are drained on worker threads (NOT one on the calling thread): a
 * hung child that never EOFs would otherwise block a calling-thread drain forever and the
 * timeout could never fire. Draining both off-thread keeps the bounded {@code waitFor} the only
 * thing the calling thread blocks on, so the deadline is always honoured.</p>
 */
@Singleton
public class MavenCommandExecutor implements CommandExecutorPort {

    private static final String MANAGER = "mvn";

    /**
     * Bounded grace to collect the drained partial output AFTER the tree is killed. The pipes
     * EOF once every fd-holder is reaped; this cap guarantees a stuck drain can never re-hang
     * the executor past the deadline it just enforced.
     */
    private static final long DRAIN_GRACE_SECONDS = 5;

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
        // BOTH pipes drain on workers so the calling thread blocks ONLY on the bounded waitFor —
        // a calling-thread drain on a hung child would never EOF and the timeout could not fire.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Process process = pb.start();
            Future<String> stdoutFuture = pool.submit(() -> drain(process.getInputStream()));
            Future<String> stderrFuture = pool.submit(() -> drain(process.getErrorStream()));

            boolean exited = process.waitFor(spec.timeoutSeconds(), TimeUnit.SECONDS);
            if (!exited) {
                // Deadline expired — kill the WHOLE tree and return the partial signal.
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
     * Kill the process tree on a timeout and collect the stdout that drains once the tree is
     * reaped. Snapshots the descendants BEFORE destroying the parent (a dead parent's children
     * reparent to init and are no longer enumerable), then forcibly destroys the parent and every
     * snapshotted descendant. A grandchild holding an inherited stdout fd keeps the pipe open
     * until it too is killed, so the drain is collected AFTER the kill, under a bounded grace.
     */
    private static String killTreeAndDrain(Process process, Future<String> stdoutFuture) {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        for (ProcessHandle child : descendants) {
            child.destroyForcibly();
        }
        return drainQuietly(stdoutFuture);
    }

    /**
     * Collect a drain future's captured output under a bounded grace, never re-hanging past the
     * deadline. A timed-out or interrupted drain yields {@code ""} (the partial output is
     * best-effort on a kill path).
     */
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
