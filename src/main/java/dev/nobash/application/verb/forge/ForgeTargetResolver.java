package dev.nobash.application.verb.forge;

import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.forge.ForgeInstance;
import dev.nobash.domain.forge.OriginRemote;
import dev.nobash.domain.forge.OriginRemoteParser;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.domain.port.out.ForgeInstancePort;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The shared target-resolution helper for the {@code pr_view}/{@code pr_diff} use-cases (PRD-6 S2,
 * #100): resolve the allowlisted {@link ForgeInstance} + {@code owner}/{@code repo} from either the
 * explicit {@code repo} override or the workspace {@code origin} remote, and validate the {@code pr}
 * number. Package-private — lives in {@code application.verb.forge} alongside {@link PrChecksUseCase}
 * (the same ArchUnit slice token "forge"), so sharing it is an intra-slice dependency, never a
 * forbidden cross-slice edge.
 *
 * <p>Deliberately duplicates (rather than extracts from) {@link PrChecksUseCase}'s own private
 * resolution methods: {@code pr_checks} is an already-shipped verb and touching it risks regression
 * for no benefit; the two NEW verbs share this helper between themselves instead.</p>
 */
final class ForgeTargetResolver {

    private static final String GIT = "git";

    private ForgeTargetResolver() {
        // Static helper — no instances.
    }

    /** Internal resolution result: either a resolved instance+slug, or a ready operational-error envelope. */
    record Resolved(ForgeInstance instance, String owner, String repo, Envelope error) {
        static Resolved of(ForgeInstance instance, String owner, String repo) {
            return new Resolved(instance, owner, repo, null);
        }

        static Resolved error(Envelope error) {
            return new Resolved(null, null, null, error);
        }
    }

    static Resolved resolveTarget(String verb, CommandExecutorPort git, ForgeInstancePort allowlist,
                                  String path, String repo, int gitTimeout) {
        if (repo != null && !repo.isBlank()) {
            return resolveFromOverride(verb, allowlist, repo);
        }
        return resolveFromOrigin(verb, git, allowlist, path, gitTimeout);
    }

    private static Resolved resolveFromOverride(String verb, ForgeInstancePort allowlist, String repo) {
        String[] parts = repo.strip().split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Resolved.error(Envelope.operationalError(verb, ErrorCode.FORGE_ORIGIN_UNRESOLVED,
                    "The `repo` override '" + repo + "' is not a valid owner/repo slug.",
                    "Pass `repo` as `owner/repo` (e.g. `octo-org/hello-world`)."));
        }
        Optional<ForgeInstance> instance = allowlist.primary();
        if (instance.isEmpty()) {
            return Resolved.error(hostNotAllowlisted(verb,
                    "no forge instance is configured in the operator allowlist"));
        }
        return Resolved.of(instance.get(), parts[0], parts[1]);
    }

    private static Resolved resolveFromOrigin(String verb, CommandExecutorPort git,
                                              ForgeInstancePort allowlist, String path, int gitTimeout) {
        Optional<Envelope> workspaceError = validateWorkspace(verb, git, path);
        if (workspaceError.isPresent()) {
            return Resolved.error(workspaceError.get());
        }

        String originUrl = gitCapture(git, path, gitTimeout, "remote", "get-url", "origin");
        if (originUrl == null || originUrl.isBlank()) {
            return Resolved.error(Envelope.operationalError(verb, ErrorCode.FORGE_ORIGIN_UNRESOLVED,
                    "Could not read the `origin` remote of the workspace.",
                    "Pass an explicit `repo` (owner/repo), or run from a checkout with an `origin` remote."));
        }

        Optional<OriginRemote> origin = OriginRemoteParser.parse(originUrl);
        if (origin.isEmpty()) {
            return Resolved.error(Envelope.operationalError(verb, ErrorCode.FORGE_ORIGIN_UNRESOLVED,
                    "The `origin` remote URL was not a recognized SSH or HTTPS form.",
                    "Pass an explicit `repo` (owner/repo)."));
        }

        OriginRemote remote = origin.get();
        Optional<ForgeInstance> instance = allowlist.resolveForHost(remote.host());
        if (instance.isEmpty()) {
            return Resolved.error(hostNotAllowlisted(verb,
                    "host '" + remote.host() + "' is not in the operator allowlist"));
        }
        return Resolved.of(instance.get(), remote.owner(), remote.repo());
    }

    private static Envelope hostNotAllowlisted(String verb, String detail) {
        return Envelope.operationalError(verb, ErrorCode.FORGE_HOST_NOT_ALLOWLISTED,
                "Forge access denied: " + detail + ".",
                "Add the forge instance to the operator allowlist (forge.instances[]) that "
                        + "MICRONAUT_CONFIG_FILES points at.");
    }

    /**
     * Validate the {@code pr} argument: required, must parse as a positive integer. Reuses
     * {@code FORGE_ORIGIN_UNRESOLVED} — the PR to inspect could not be determined, mirroring the
     * unresolvable-repo case (no PR-branch-resolution fallback is built; explicit `pr` is required, D62(5)).
     */
    static Envelope validatePr(String verb, String pr, long[] out) {
        if (pr == null || pr.isBlank()) {
            return Envelope.operationalError(verb, ErrorCode.FORGE_ORIGIN_UNRESOLVED,
                    "No `pr` number was supplied.",
                    "Pass the pull request number as `pr` (e.g. `pr: 42`).");
        }
        try {
            long value = Long.parseLong(pr.strip());
            if (value <= 0) {
                throw new NumberFormatException("non-positive");
            }
            out[0] = value;
            return null;
        } catch (NumberFormatException e) {
            return Envelope.operationalError(verb, ErrorCode.FORGE_ORIGIN_UNRESOLVED,
                    "The `pr` value '" + pr + "' is not a valid positive integer.",
                    "Pass the pull request number as `pr` (e.g. `pr: 42`).");
        }
    }

    static Optional<Envelope> validateWorkspace(String verb, CommandExecutorPort git, String path) {
        if (path == null || path.isBlank()) {
            return Optional.of(Envelope.operationalError(verb, ErrorCode.INVALID_PATH,
                    "No path was provided.",
                    "Pass the path to the repository checkout (or an explicit `repo`)."));
        }
        final Path dir;
        try {
            dir = Path.of(path);
        } catch (InvalidPathException e) {
            return Optional.of(Envelope.operationalError(verb, ErrorCode.INVALID_PATH,
                    "Path '" + path + "' is not a valid path.",
                    "Pass the path to an existing repository checkout."));
        }
        if (!Files.isDirectory(dir)) {
            return Optional.of(Envelope.operationalError(verb, ErrorCode.INVALID_PATH,
                    "Path '" + path + "' does not exist or is not a directory.",
                    "Pass the path to an existing repository checkout."));
        }
        if (!git.isManagerInstalled()) {
            return Optional.of(Envelope.operationalError(verb, ErrorCode.TOOL_NOT_INSTALLED,
                    "The 'git' tool is not installed on PATH.",
                    "Install git and ensure it is on the system PATH."));
        }
        return Optional.empty();
    }

    /** Run a git subcommand in {@code path} and return trimmed stdout, or null on a non-zero exit. */
    static String gitCapture(CommandExecutorPort git, String path, int timeout, String... args) {
        List<String> argv = new ArrayList<>();
        argv.add(GIT);
        argv.addAll(List.of(args));
        ExecResult result = git.execute(new ExecSpec(argv, path, timeout));
        if (result.timedOut() || result.exitCode() != 0) {
            return null;
        }
        return result.stdout();
    }

    static int clampTimeout(Integer timeout, int defaultSeconds, int maxSeconds) {
        if (timeout == null) {
            return defaultSeconds;
        }
        return Math.max(1, Math.min(timeout, maxSeconds));
    }
}
