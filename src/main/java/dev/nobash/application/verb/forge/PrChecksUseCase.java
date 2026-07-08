package dev.nobash.application.verb.forge;

import dev.nobash.application.forge.ForgeLogHandleRegistry;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.forge.ForgeAccessException;
import dev.nobash.domain.forge.ForgeCheckClassifier;
import dev.nobash.domain.forge.ForgeInstance;
import dev.nobash.domain.forge.OriginRemote;
import dev.nobash.domain.forge.OriginRemoteParser;
import dev.nobash.domain.forge.PrCheck;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.domain.port.out.ForgeCheckRequest;
import dev.nobash.domain.port.out.ForgeCheckRun;
import dev.nobash.domain.port.out.ForgeInstancePort;
import dev.nobash.domain.port.out.ForgeLogRequest;
import dev.nobash.domain.port.out.ForgePort;
import dev.nobash.domain.result.ContainerFinding;
import dev.nobash.domain.result.ContainerScope;
import dev.nobash.domain.result.Finding;
import dev.nobash.domain.result.Outcome;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The {@code pr_checks} use-case (PRD-6 S1, #99) — one narrow read-only path through every layer:
 * resolve the workspace {@code origin} (or the explicit {@code repo} override) against the operator
 * allowlist, fetch and fold the ref's check-runs + Commit Statuses via {@link ForgePort}, and return
 * the common envelope with a container-aware {@code ok} and per-check handles.
 *
 * <p><b>Fail-closed guard order, every gate BEFORE any I/O:</b></p>
 * <ol>
 *   <li>Resolve the target: {@code repo} override → the primary allowlisted instance; else parse
 *       {@code origin} (needs a valid workspace {@code path} + git on PATH) and match its host against
 *       the allowlist. No host → {@code FORGE_ORIGIN_UNRESOLVED}; host not allowlisted →
 *       {@code FORGE_HOST_NOT_ALLOWLISTED} (the SSRF floor — the host is never agent-supplied).</li>
 *   <li>Resolve the ref: the explicit {@code ref}, else {@code git rev-parse HEAD}.</li>
 *   <li>Call the forge; a {@link ForgeAccessException} (rate-limit / 404-private / unexpected) is
 *       mapped to a structured operational error — never rethrown across the Envelope boundary.</li>
 * </ol>
 *
 * <p><b>Container-aware {@code ok} (G5).</b> {@code ok} is {@link ForgeCheckClassifier#ok(List)}: one
 * failing check-run OR a red commit status OR an incomplete (queued/in_progress) check flips it false.
 * Each FAILING check-run becomes a top-level {@code ContainerFinding(RUN)} and — when it has a job id —
 * carries a {@code get_log} handle registered in {@link ForgeLogHandleRegistry} for the lazy 302 flow.
 * {@code manager} is null (forge is ecosystem-agnostic).</p>
 */
@Singleton
public class PrChecksUseCase {

    private static final String VERB = "pr_checks";
    private static final String GIT = "git";
    private static final int GIT_TIMEOUT_DEFAULT = 30;
    private static final int GIT_TIMEOUT_MAX = 120;

    private final CommandExecutorPort git;
    private final ForgeInstancePort allowlist;
    private final ForgePort forge;
    private final ForgeLogHandleRegistry logHandles;

    public PrChecksUseCase(@Named(GIT) CommandExecutorPort git,
                           ForgeInstancePort allowlist,
                           ForgePort forge,
                           ForgeLogHandleRegistry logHandles) {
        this.git = git;
        this.allowlist = allowlist;
        this.forge = forge;
        this.logHandles = logHandles;
    }

    /**
     * Run {@code pr_checks} for the ref of a repository resolved from the workspace or the override.
     *
     * @param path    the repository checkout directory (used to resolve {@code origin} and {@code HEAD})
     * @param ref     optional commit ref; when absent, resolved via {@code git rev-parse HEAD}
     * @param repo    optional {@code owner/repo} override (fork workflows / host aliases, D62(5))
     * @param timeout optional git-call timeout in seconds; clamped to [1, {@value GIT_TIMEOUT_MAX}]
     * @return the pr_checks envelope, or a structured operational error
     */
    public Envelope run(String path, String ref, String repo, Integer timeout) {
        int gitTimeout = clampTimeout(timeout);

        Resolved resolved = resolveTarget(path, repo, gitTimeout);
        if (resolved.error() != null) {
            return resolved.error();
        }

        String resolvedRef = resolveRef(path, ref, gitTimeout);
        if (resolvedRef == null) {
            return Envelope.operationalError(VERB, ErrorCode.FORGE_ORIGIN_UNRESOLVED,
                    "No ref was supplied and the current HEAD could not be resolved.",
                    "Pass an explicit `ref` (a branch, tag, or commit SHA).");
        }

        ForgeInstance instance = resolved.instance();
        ForgeCheckRequest request = new ForgeCheckRequest(
                instance.baseUrl(), instance.apiPrefix(), instance.tokenEnv(),
                resolved.owner(), resolved.repo(), resolvedRef);

        try {
            List<ForgeCheckRun> runs = forge.fetchChecks(request);
            return fold(instance, resolved.owner(), resolved.repo(), runs);
        } catch (ForgeAccessException e) {
            return Envelope.operationalError(VERB, e.code(), e.getMessage(), e.hint());
        }
    }

    // ---- target resolution ----

    private Resolved resolveTarget(String path, String repo, int gitTimeout) {
        if (repo != null && !repo.isBlank()) {
            return resolveFromOverride(repo);
        }
        return resolveFromOrigin(path, gitTimeout);
    }

    private Resolved resolveFromOverride(String repo) {
        String[] parts = repo.strip().split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Resolved.error(Envelope.operationalError(VERB, ErrorCode.FORGE_ORIGIN_UNRESOLVED,
                    "The `repo` override '" + repo + "' is not a valid owner/repo slug.",
                    "Pass `repo` as `owner/repo` (e.g. `octo-org/hello-world`)."));
        }
        Optional<ForgeInstance> instance = allowlist.primary();
        if (instance.isEmpty()) {
            return Resolved.error(hostNotAllowlisted(
                    "no forge instance is configured in the operator allowlist"));
        }
        return Resolved.of(instance.get(), parts[0], parts[1]);
    }

    private Resolved resolveFromOrigin(String path, int gitTimeout) {
        Optional<Envelope> workspaceError = validateWorkspace(path);
        if (workspaceError.isPresent()) {
            return Resolved.error(workspaceError.get());
        }

        String originUrl = gitCapture(path, gitTimeout, "remote", "get-url", "origin");
        if (originUrl == null || originUrl.isBlank()) {
            return Resolved.error(Envelope.operationalError(VERB, ErrorCode.FORGE_ORIGIN_UNRESOLVED,
                    "Could not read the `origin` remote of the workspace.",
                    "Pass an explicit `repo` (owner/repo), or run from a checkout with an `origin` remote."));
        }

        Optional<OriginRemote> origin = OriginRemoteParser.parse(originUrl);
        if (origin.isEmpty()) {
            return Resolved.error(Envelope.operationalError(VERB, ErrorCode.FORGE_ORIGIN_UNRESOLVED,
                    "The `origin` remote URL was not a recognized SSH or HTTPS form.",
                    "Pass an explicit `repo` (owner/repo)."));
        }

        OriginRemote remote = origin.get();
        Optional<ForgeInstance> instance = allowlist.resolveForHost(remote.host());
        if (instance.isEmpty()) {
            return Resolved.error(hostNotAllowlisted(
                    "host '" + remote.host() + "' is not in the operator allowlist"));
        }
        return Resolved.of(instance.get(), remote.owner(), remote.repo());
    }

    private Envelope hostNotAllowlisted(String detail) {
        return Envelope.operationalError(VERB, ErrorCode.FORGE_HOST_NOT_ALLOWLISTED,
                "Forge access denied: " + detail + ".",
                "Add the forge instance to the operator allowlist (forge.instances[]) that "
                        + "MICRONAUT_CONFIG_FILES points at.");
    }

    // ---- ref resolution ----

    private String resolveRef(String path, String ref, int gitTimeout) {
        if (ref != null && !ref.isBlank()) {
            return ref.strip();
        }
        if (validateWorkspace(path).isPresent()) {
            return null;
        }
        String head = gitCapture(path, gitTimeout, "rev-parse", "HEAD");
        return (head == null || head.isBlank()) ? null : head.strip();
    }

    // ---- fold ----

    private Envelope fold(ForgeInstance instance, String owner, String repo, List<ForgeCheckRun> runs) {
        List<PrCheck> checks = new ArrayList<>();
        List<Finding> failures = new ArrayList<>();

        for (ForgeCheckRun run : runs) {
            String conclusion = run.conclusion() != null ? run.conclusion() : run.status();
            String handle = null;

            if (ForgeCheckClassifier.isFailing(run)) {
                if (run.jobId() != null) {
                    handle = logHandles.register(new ForgeLogRequest(
                            instance.baseUrl(), instance.apiPrefix(), instance.tokenEnv(),
                            owner, repo, run.jobId()));
                }
                failures.add(new ContainerFinding(
                        ContainerScope.RUN, run.name(), Outcome.FAILED, conclusion,
                        "Check '" + run.name() + "' concluded '" + conclusion + "'.", null, null));
            }
            // Incomplete (queued/in_progress) checks flip ok via the classifier but are NOT findings —
            // they have not concluded, so there is nothing to triage; they still appear in prChecks[].

            checks.add(new PrCheck(run.name(), conclusion, handle));
        }

        boolean ok = ForgeCheckClassifier.ok(runs);
        return Envelope.prChecks(VERB, ok, checks, failures);
    }

    // ---- git helpers ----

    private Optional<Envelope> validateWorkspace(String path) {
        if (path == null || path.isBlank()) {
            return Optional.of(Envelope.operationalError(VERB, ErrorCode.INVALID_PATH,
                    "No path was provided.",
                    "Pass the path to the repository checkout (or an explicit `repo` and `ref`)."));
        }
        final Path dir;
        try {
            dir = Path.of(path);
        } catch (InvalidPathException e) {
            return Optional.of(Envelope.operationalError(VERB, ErrorCode.INVALID_PATH,
                    "Path '" + path + "' is not a valid path.",
                    "Pass the path to an existing repository checkout."));
        }
        if (!Files.isDirectory(dir)) {
            return Optional.of(Envelope.operationalError(VERB, ErrorCode.INVALID_PATH,
                    "Path '" + path + "' does not exist or is not a directory.",
                    "Pass the path to an existing repository checkout."));
        }
        if (!git.isManagerInstalled()) {
            return Optional.of(Envelope.operationalError(VERB, ErrorCode.TOOL_NOT_INSTALLED,
                    "The 'git' tool is not installed on PATH.",
                    "Install git and ensure it is on the system PATH."));
        }
        return Optional.empty();
    }

    /** Run a git subcommand in {@code path} and return trimmed stdout, or null on a non-zero exit. */
    private String gitCapture(String path, int timeout, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add(GIT);
        argv.addAll(List.of(args));
        ExecResult result = git.execute(new ExecSpec(argv, path, timeout));
        if (result.timedOut() || result.exitCode() != 0) {
            return null;
        }
        return result.stdout();
    }

    private static int clampTimeout(Integer timeout) {
        if (timeout == null) {
            return GIT_TIMEOUT_DEFAULT;
        }
        return Math.max(1, Math.min(timeout, GIT_TIMEOUT_MAX));
    }

    /** Internal resolution result: either a resolved instance+slug, or a ready operational-error envelope. */
    private record Resolved(ForgeInstance instance, String owner, String repo, Envelope error) {
        static Resolved of(ForgeInstance instance, String owner, String repo) {
            return new Resolved(instance, owner, repo, null);
        }

        static Resolved error(Envelope error) {
            return new Resolved(null, null, null, error);
        }
    }
}
