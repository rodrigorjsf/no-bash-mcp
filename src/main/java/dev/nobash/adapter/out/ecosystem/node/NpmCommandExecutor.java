package dev.nobash.adapter.out.ecosystem.node;

import dev.nobash.adapter.out.ecosystem.ManagerPathResolver;
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
 * The Node/npm outbound execution adapter (PRD-3, slice 3). Satisfies {@link CommandExecutorPort}
 * for the Node ecosystem under the qualifier {@code "npm"}. It (a) reports whether the trusted
 * system {@code npm} is installed on PATH via the injected {@link ManagerPathResolver} seam, and
 * (b) launches commands by spawning the explicit {@code argv} array directly.
 *
 * <p>Execution launches {@code new ProcessBuilder(spec.argv())} — the argv array is passed
 * verbatim, NEVER through {@code /bin/sh -c} and never re-split, so shell metacharacters in any
 * token are inert (security-model.md, ADR-0008). {@code argv[0]} is the trusted system {@code npm}
 * name resolved on {@code PATH} by the OS — never a repo wrapper, per ADR-0008.</p>
 *
 * <p>Timeout enforcement mirrors the Maven executor (issue #6): bounded {@code waitFor}, tree-kill,
 * and dual off-thread drains so the deadline is always honoured.</p>
 *
 * <p><b>DI qualifier (PRD-3, slice 3).</b> This bean is {@link Named} {@code "npm"} so that
 * {@link dev.nobash.application.verb.install.InstallUseCase} resolves it explicitly rather than
 * the {@link io.micronaut.context.annotation.Primary} Maven executor. The primary Maven bean is
 * left unchanged — all existing callers continue to resolve it without qualification.</p>
 */
@Singleton
@Named("npm")
public class NpmCommandExecutor implements CommandExecutorPort {

    private static final String MANAGER = "npm";

    /**
     * Bounded grace to collect the drained partial output AFTER the tree is killed. The pipes
     * EOF once every fd-holder is reaped; this cap guarantees a stuck drain can never re-hang
     * the executor past the deadline it just enforced.
     */
    private static final long DRAIN_GRACE_SECONDS = 5;

    private final ManagerPathResolver resolver;

    public NpmCommandExecutor(ManagerPathResolver resolver) {
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

    private static String killTreeAndDrain(Process process, Future<String> stdoutFuture) {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        for (ProcessHandle child : descendants) {
            child.destroyForcibly();
        }
        return drainQuietly(stdoutFuture);
    }

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
     * Build the {@link ProcessBuilder} for a spec. Package-visible for unit testing.
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
