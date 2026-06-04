package dev.nobash.application.verb.tests;

import dev.nobash.application.policy.TestsFlagPolicy;
import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.infra.concurrency.ModuleLock;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The fail-fast per-module concurrency guard (ADR-0005, D22, issue #6 AC1/AC2). A second mutating
 * {@code run_tests} on the SAME module while the first holds the per-module lock returns
 * {@code RESOURCE_BUSY} <em>without blocking</em>; runs on DIFFERENT modules proceed concurrently.
 *
 * <p>The tests are deterministic, never sleep/timing-dependent: a {@link CountDownLatch} barrier
 * holds the first run inside {@code executor.execute()} (so the lock is provably held) while a
 * second call is issued. The lock key is {@code realpath(moduleDir)} — proven by colliding a
 * symlink alias against its canonical target.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RunTestsConcurrencyTest {

    private static final long BARRIER_TIMEOUT_SECONDS = 10;

    /**
     * An executor stub that, on {@code execute()}, counts down {@code entered} (the lock is now
     * held by THIS run) then blocks until {@code release} is opened — modelling a long-running
     * build holding the module lock. It writes a real PASSED report so the verb completes cleanly
     * once released.
     */
    private static final class BarrierExecutor implements CommandExecutorPort {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        BarrierExecutor(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public boolean isManagerInstalled() {
            return true;
        }

        @Override
        public ExecResult execute(ExecSpec spec) {
            entered.countDown();
            try {
                if (!release.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new AssertionError("release latch was never opened — barrier deadlock");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted at the barrier", e);
            }
            writePassedReport(spec);
            return new ExecResult(0, "", "", false);
        }
    }

    private static RunTestsUseCase useCaseWith(CommandExecutorPort port, ModuleLock lock) {
        return new RunTestsUseCase(port, new ArgvBuilder(), new TestsFlagPolicy(),
                new RawOutputStash(), lock);
    }

    private static Path mavenProject(Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        return dir;
    }

    @Nested
    class same_module_collision_fails_fast_AC1 {

        @Test
        void a_second_run_on_the_same_module_returns_RESOURCE_BUSY_while_the_first_holds_the_lock(
                @TempDir Path dir) throws Exception {
            mavenProject(dir);
            ModuleLock lock = new ModuleLock();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            RunTestsUseCase useCase = useCaseWith(new BarrierExecutor(entered, release), lock);

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                // Run #1 acquires the lock and blocks inside execute() at the barrier.
                Future<Envelope> first = pool.submit(() -> useCase.run(dir.toString(), List.of(), null));
                assertThat(entered.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("the first run must reach execute() and hold the lock").isTrue();

                // Run #2 on the SAME module, issued while #1 still holds the lock, fails fast.
                Envelope second = useCase.run(dir.toString(), List.of(), null);

                assertThat(second.ok()).isFalse();
                assertThat(second.error()).isNotNull();
                assertThat(second.error().code()).isEqualTo(ErrorCode.RESOURCE_BUSY);
                assertThat(second.error().hint()).isNotBlank();

                // Now free #1 and confirm it completed normally (and released the lock).
                release.countDown();
                Envelope firstResult = first.get(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(firstResult.ok()).as("the first run completes once released").isTrue();
                assertThat(lock.isHeldFor(dir)).as("the lock is released after #1 finishes").isFalse();
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }

        @Test
        void after_the_first_run_releases_the_same_module_can_run_again(@TempDir Path dir) throws Exception {
            mavenProject(dir);
            ModuleLock lock = new ModuleLock();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            // Open the release immediately so the first run completes synchronously and frees the lock.
            release.countDown();
            RunTestsUseCase useCase = useCaseWith(new BarrierExecutor(entered, release), lock);

            Envelope first = useCase.run(dir.toString(), List.of(), null);
            assertThat(first.ok()).isTrue();

            // A second sequential run on the same module is NOT RESOURCE_BUSY — the lock was freed.
            Envelope second = useCase.run(dir.toString(), List.of(), null);
            assertThat(second.ok()).isTrue();
        }

        @Test
        void a_symlink_alias_of_the_same_module_collapses_to_the_same_realpath_key(
                @TempDir Path tmp) throws Exception {
            // realpath(moduleDir) keying: a symlink alias and its canonical target are the SAME
            // key, so a concurrent run via the alias still collides. POSIX symlink — skip on Windows.
            assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                    "POSIX symlink fixture — Windows symlink semantics differ");
            Path real = mavenProject(Files.createDirectory(tmp.resolve("real-module")));
            Path alias = tmp.resolve("alias-module");
            Files.createSymbolicLink(alias, real);

            ModuleLock lock = new ModuleLock();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            RunTestsUseCase useCase = useCaseWith(new BarrierExecutor(entered, release), lock);

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                // Run #1 via the REAL path holds the lock.
                Future<Envelope> first = pool.submit(() -> useCase.run(real.toString(), List.of(), null));
                assertThat(entered.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

                // Run #2 via the ALIAS path must collide — same realpath key.
                Envelope viaAlias = useCase.run(alias.toString(), List.of(), null);

                assertThat(viaAlias.error()).isNotNull();
                assertThat(viaAlias.error().code())
                        .as("an alias path collapses to the same realpath key and collides")
                        .isEqualTo(ErrorCode.RESOURCE_BUSY);

                release.countDown();
                first.get(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Nested
    class different_modules_proceed_concurrently_AC2 {

        @Test
        void two_runs_on_different_modules_both_reach_execution_a_two_latch_barrier_proof(
                @TempDir Path tmp) throws Exception {
            Path moduleA = mavenProject(Files.createDirectory(tmp.resolve("module-a")));
            Path moduleB = mavenProject(Files.createDirectory(tmp.resolve("module-b")));
            ModuleLock lock = new ModuleLock();

            // The two-latch barrier: BOTH runs must reach execute() and arrive at the shared barrier
            // before EITHER is released. A wrongly-global lock would RESOURCE_BUSY (or deadlock) the
            // second run, it would never reach the barrier, and bothArrived would time out — failing.
            CountDownLatch bothEntered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger reached = new AtomicInteger();

            CommandExecutorPort barrierBoth = new CommandExecutorPort() {
                @Override
                public boolean isManagerInstalled() {
                    return true;
                }

                @Override
                public ExecResult execute(ExecSpec spec) {
                    reached.incrementAndGet();
                    bothEntered.countDown();
                    try {
                        // Wait until BOTH have arrived (proves true concurrency), then proceed.
                        if (!bothEntered.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            throw new AssertionError("both runs did not reach the barrier — a global "
                                    + "lock serialized them");
                        }
                        release.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted at the two-latch barrier", e);
                    }
                    writePassedReport(spec);
                    return new ExecResult(0, "", "", false);
                }
            };
            RunTestsUseCase useCase = useCaseWith(barrierBoth, lock);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Envelope> runA = pool.submit(() -> useCase.run(moduleA.toString(), List.of(), null));
                Future<Envelope> runB = pool.submit(() -> useCase.run(moduleB.toString(), List.of(), null));

                // BOTH must arrive at the barrier — the concurrency proof. If the lock were global,
                // only one would reach execute() and this await would time out.
                assertThat(bothEntered.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("both different-module runs must reach execution concurrently").isTrue();
                assertThat(reached.get()).isEqualTo(2);

                release.countDown();
                assertThat(runA.get(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS).ok()).isTrue();
                assertThat(runB.get(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS).ok()).isTrue();
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
    }

    /** Write a real PASSED Surefire report into the spec's MCP-injected reports dir. */
    private static void writePassedReport(ExecSpec spec) {
        String token = spec.argv().stream()
                .filter(a -> a.startsWith("-Dsurefire.reportsDirectory="))
                .findFirst().orElseThrow(() -> new AssertionError("no reportsDirectory injected"));
        Path dir = Path.of(token.substring("-Dsurefire.reportsDirectory=".length()));
        try {
            Files.createDirectories(dir);
            byte[] xml;
            try (var in = RunTestsConcurrencyTest.class.getClassLoader()
                    .getResourceAsStream("fixtures/maven/surefire-all-passed.xml")) {
                if (in == null) {
                    throw new IllegalStateException("missing fixture surefire-all-passed.xml");
                }
                xml = in.readAllBytes();
            }
            Files.write(dir.resolve("TEST-passed.xml"), xml);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write the passed report fixture", e);
        }
    }
}
