package dev.nobash.domain.envelope;

import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.error.OperationalError;
import dev.nobash.domain.forge.PrCheck;
import dev.nobash.domain.forge.PrChecksSummary;
import dev.nobash.domain.forge.PrView;
import dev.nobash.domain.git.GitCommit;
import dev.nobash.domain.git.GitCommitDetail;
import dev.nobash.domain.git.GitBranchEntry;
import dev.nobash.domain.git.GitDiffEntry;
import dev.nobash.domain.git.GitStatus;
import dev.nobash.domain.git.GitStatusEntry;
import dev.nobash.domain.result.BuildSummary;
import dev.nobash.domain.result.CompileDiagnostic;
import dev.nobash.domain.result.ContainerFinding;
import dev.nobash.domain.result.Finding;
import dev.nobash.domain.result.InstallSummary;
import dev.nobash.domain.result.SourceRef;
import dev.nobash.domain.result.TestFinding;
import dev.nobash.domain.result.Summary;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.jsonschema.JsonSchema;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/**
 * The common result shape every verb returns (CONTEXT.md "Envelope"). It has three shapes,
 * distinguished by which optional fields are present (D30, DESIGN §6):
 *
 * <ul>
 *   <li><b>success</b> — {@code ok=true}, counts-only ({@code summary} present, NO {@code failures},
 *       NO report surfaced). Built ONLY when tests actually executed.</li>
 *   <li><b>test-failure</b> — {@code ok=false}, {@code summary} + top-level {@code failures[]} (each
 *       a {@link Finding} serialized with its frozen {@code "kind"} discriminator so the agent
 *       branches {@code TestFinding} vs {@code ContainerFinding}).</li>
 *   <li><b>operational-error</b> — {@code ok=false}, an {@link OperationalError} ({@code code} +
 *       {@code message} + {@code hint}); a {@link Handle} is attached when raw output was stashed
 *       (e.g. {@code REPORT_NOT_PRODUCED} carries the compiler output behind the handle).</li>
 * </ul>
 *
 * <p>Pure domain, reflection-free ({@code @Serdeable @Introspected}) for STDIO serialization
 * without runtime reflection (DESIGN.md §7). Optional wire fields are reference types so they
 * emit {@code null} rather than a misleading default.</p>
 *
 * <p><b>P9 prompt-injection defense (issue #7).</b> The test-failure shape is the one that
 * carries <em>repo-derived</em> content (test names, assertion messages, source paths, stack
 * traces). Those strings pass through {@link OutboundNeutralizer} when the envelope is built:
 * control/ANSI/zero-width sequences are stripped, per-field caps are applied, and the envelope
 * is marked {@link #untrusted} so consumers know the failures list carries untrusted data.
 * The success and operational-error shapes carry only server-authored content; they are not
 * neutralized and are marked {@code untrusted=false}.</p>
 *
 * <p><b>git-status shape (PRD-002).</b> The read-only git verbs add a fourth carrier:
 * {@code gitStatus} holds the normalized {@link GitStatus} parsed from {@code git status
 * --porcelain=v2 --branch}. {@code manager} is null on a git envelope (git is ecosystem-agnostic).
 * Branch names and file paths are repo-derived, so they are P9-neutralized when the envelope is
 * built and the envelope is marked {@code untrusted=true}, mirroring the build compile-failure
 * shape.</p>
 *
 * @param ok              whether the operation itself succeeded (the application-layer failure floor)
 * @param verb            the logical operation invoked (e.g. {@code run_tests}, {@code build})
 * @param manager         the detected manager for ecosystem verbs ({@code mvn}); null for git verbs
 * @param summary         the TEST-level counts; present on run_tests success/failure, null otherwise
 * @param failures        the normalized test findings; present only on run_tests test-failure
 * @param diagnostics     the compile diagnostics; present only on build compile-failure (ADR-0009)
 * @param buildSummary    the compile-level counts ({@code errors}/{@code warnings}); present on
 *                        build success and build compile-failure (ADR-0009)
 * @param gitStatus       the normalized git-status shape; present only on a git_status result (PRD-002)
 * @param gitLog          the capped commit list; present only on a git_log result (PRD-002, issue #26)
 * @param gitShow         the commit detail (metadata + body); present only on a git_show result
 *                        (PRD-002, issue #26); the diff is behind the {@code handle}
 * @param gitDiff         the inline diff file summary ({@code files[]}); present only on a
 *                        git_diff result (PRD-002, issue #27); the full patch is behind the
 *                        {@code handle}
 * @param gitBranch       the normalized branch list; present only on a git_branch result
 *                        (PRD-002, issue #28); output is bounded — no stash/handle needed
 * @param installSummary  the npm install counts ({@code added/removed/changed}); present only on
 *                        an {@code install} success envelope (PRD-3, slice 3)
 * @param error           the operational error when this is the op-error shape; null otherwise
 * @param handle          a token to retrieve stashed raw output later; null when nothing was stashed
 * @param untrusted       {@code true} when this envelope carries repo-derived content that has been
 *                        neutralized but is still untrusted data ({@code failures[]},
 *                        {@code diagnostics[]}, {@code gitStatus}, {@code gitLog},
 *                        {@code gitShow}, {@code gitDiff}, {@code gitBranch}, or {@code prChecks})
 * @param prChecks        the per-check CI list ({@code name}/{@code conclusion}/{@code handle});
 *                        present only on a {@code pr_checks} result (PRD-6 S1, #99). Check names are
 *                        repo-derived, so they are P9-neutralized and the envelope is marked
 *                        {@code untrusted=true}
 * @param prView          the one-call PR metadata carrier; present only on a {@code pr_view} result
 *                        (PRD-6 S2, #100). {@code headRef}/{@code baseRef} are repo-derived, so they
 *                        are P9-neutralized and the envelope is marked {@code untrusted=true}
 */
@Serdeable
@Introspected
@JsonSchema
public record Envelope(boolean ok,
                       String verb,
                       @Nullable String manager,
                       @Nullable Summary summary,
                       @Nullable List<Finding> failures,
                       @Nullable List<CompileDiagnostic> diagnostics,
                       @Nullable BuildSummary buildSummary,
                       @Nullable GitStatus gitStatus,
                       @Nullable List<GitCommit> gitLog,
                       @Nullable GitCommitDetail gitShow,
                       @Nullable List<GitDiffEntry> gitDiff,
                       @Nullable List<GitBranchEntry> gitBranch,
                       @Nullable InstallSummary installSummary,
                       @Nullable OperationalError error,
                       @Nullable Handle handle,
                       boolean untrusted,
                       @Nullable List<PrCheck> prChecks,
                       @Nullable PrView prView) {

    /**
     * Build a counts-only success envelope ({@code ok=true}) for {@code run_tests}. Surfaces NO
     * report — a green run gives the agent the counts and nothing to triage (token efficiency,
     * CONTEXT.md "Noise"). Server-authored content only; marked {@code untrusted=false}.
     */
    public static Envelope success(String verb, String manager, Summary summary, @Nullable Handle handle) {
        return new Envelope(true, verb, manager, summary, null, null, null, null, null, null, null, null, null, null, handle, false, null, null);
    }

    /**
     * Build a counts-only success envelope ({@code ok=true}) for the {@code build} verb (ADR-0009).
     * Returns {@code buildSummary:{errors:0,warnings:N}} and no {@code diagnostics[]}.
     * Server-authored content only; marked {@code untrusted=false}.
     */
    public static Envelope buildSuccess(String verb, String manager, BuildSummary buildSummary,
                                        @Nullable Handle handle) {
        return new Envelope(true, verb, manager, null, null, null, buildSummary, null, null, null, null, null, null, null, handle, false, null, null);
    }

    /**
     * Build a compile-failure envelope ({@code ok=false}) for the {@code build} verb (ADR-0009).
     * Carries {@code diagnostics[]} (parse of the compiler output) and {@code buildSummary} with
     * the error/warning counts. The {@code message} field of each {@link CompileDiagnostic} is
     * repo-derived; the diagnostics list is P9-neutralized before storing, and the envelope is
     * marked {@code untrusted=true}.
     */
    public static Envelope buildFailure(String verb, String manager, BuildSummary buildSummary,
                                        List<CompileDiagnostic> diagnostics, @Nullable Handle handle) {
        List<CompileDiagnostic> neutralized = diagnostics.stream()
                .map(Envelope::neutralizeDiagnostic)
                .toList();
        return new Envelope(false, verb, manager, null, null, List.copyOf(neutralized),
                buildSummary, null, null, null, null, null, null, null, handle, true, null, null);
    }

    /**
     * Build a test-failure envelope ({@code ok=false}) carrying the normalized {@code failures[]}.
     *
     * <p><b>P9 neutralization:</b> every repo-derived string in each {@link Finding} is passed
     * through {@link OutboundNeutralizer} before being stored in the envelope. Control chars,
     * ANSI escape sequences, and zero-width/bidi code points are stripped; per-field caps are
     * applied. The envelope is marked {@code untrusted=true} to signal that {@code failures[]}
     * carries untrusted data.</p>
     *
     * <p>The failure floor adds only the boolean — it NEVER injects a synthetic finding, so each
     * finding appears exactly once and {@code summary} counts are unchanged (D28/D30).</p>
     */
    public static Envelope testFailure(String verb, String manager, Summary summary,
                                       List<Finding> failures, @Nullable Handle handle) {
        List<Finding> neutralized = failures.stream()
                .map(Envelope::neutralizeFinding)
                .toList();
        return new Envelope(false, verb, manager, summary, List.copyOf(neutralized), null, null, null, null, null, null, null, null, null, handle, true, null, null);
    }

    /**
     * Build a git-status success envelope ({@code ok=true}) carrying the normalized
     * {@link GitStatus} (PRD-002, issue #24). {@code manager} is null — git verbs detect no
     * package manager.
     *
     * <p><b>P9 neutralization:</b> the branch name, upstream, and every changed-path entry in the
     * status are repo-derived (a repo can name a branch or file with ANSI/zero-width bytes), so
     * each string is passed through {@link OutboundNeutralizer} before being stored. The envelope
     * is marked {@code untrusted=true}, mirroring the build compile-failure shape — git_status is
     * the foundational read-only git shape and the later git verbs inherit this discipline.</p>
     */
    public static Envelope gitStatus(String verb, GitStatus status, @Nullable Handle handle) {
        return new Envelope(true, verb, null, null, null, null, null,
                neutralizeGitStatus(status), null, null, null, null, null, null, handle, true, null, null);
    }

    /**
     * Build a git-log success envelope ({@code ok=true}) carrying the capped commit list (PRD-002,
     * issue #26). {@code manager} is null — git verbs detect no package manager.
     *
     * <p><b>P9 neutralization:</b> author and subject are repo-derived and are P9-neutralized
     * before storing. sha/abbrev/dateIso are git-generated and safe. The envelope is marked
     * {@code untrusted=true}.</p>
     *
     * @param verb    the verb name ({@code "git_log"})
     * @param commits the capped commit list to include in the envelope
     * @return the git-log envelope
     */
    public static Envelope gitLog(String verb, List<GitCommit> commits) {
        List<GitCommit> neutralized = commits.stream()
                .map(Envelope::neutralizeGitCommit)
                .toList();
        return new Envelope(true, verb, null, null, null, null, null, null,
                List.copyOf(neutralized), null, null, null, null, null, null, true, null, null);
    }

    /**
     * Build a git-show success envelope ({@code ok=true}) carrying the commit detail (PRD-002,
     * issue #26). The diff is stashed behind {@code handle} and retrievable via {@code get_log}.
     *
     * <p><b>P9 neutralization:</b> author, subject, and body are repo-derived and P9-neutralized.
     * sha/abbrev/dateIso are git-generated and safe. The envelope is marked {@code untrusted=true}.</p>
     *
     * @param verb   the verb name ({@code "git_show"})
     * @param detail the commit detail (metadata + body; diff is NOT embedded here)
     * @param handle the handle pointing at the stashed diff raw output; may be null
     * @return the git-show envelope
     */
    public static Envelope gitShow(String verb, GitCommitDetail detail, @Nullable Handle handle) {
        return new Envelope(true, verb, null, null, null, null, null, null, null,
                neutralizeGitCommitDetail(detail), null, null, null, null, handle, true, null, null);
    }

    /**
     * Build a git-diff success envelope ({@code ok=true}) carrying the inline diff summary
     * ({@code gitDiff[]}) (PRD-002, issue #27). The full patch is stashed behind {@code handle}
     * and retrievable via {@code get_log}. {@code manager} is null — git verbs detect no package
     * manager.
     *
     * <p><b>P9 neutralization:</b> the {@code path} field of each {@link GitDiffEntry} is
     * repo-derived and is P9-neutralized before storing ({@code SOURCE_FILE_CAP}). The
     * {@code status} letter is a server-controlled value and is not neutralized. The envelope is
     * marked {@code untrusted=true}.</p>
     *
     * @param verb    the verb name ({@code "git_diff"})
     * @param entries the inline diff file summary (path, added, deleted, status)
     * @param handle  the handle pointing at the stashed full patch output; may be null
     * @return the git-diff envelope
     */
    public static Envelope gitDiff(String verb, List<GitDiffEntry> entries, @Nullable Handle handle) {
        List<GitDiffEntry> neutralized = entries.stream()
                .map(Envelope::neutralizeGitDiffEntry)
                .toList();
        return new Envelope(true, verb, null, null, null, null, null, null, null, null,
                List.copyOf(neutralized), null, null, null, handle, true, null, null);
    }

    /**
     * Build a git-branch success envelope ({@code ok=true}) carrying the normalized branch list
     * (PRD-002, issue #28). {@code manager} is null — git verbs detect no package manager.
     * There is no handle: {@code git branch} output is bounded (one line per local branch) and
     * never large enough to warrant a stash/pagination.
     *
     * <p><b>P9 neutralization:</b> the {@code name} and {@code upstream} fields of each
     * {@link GitBranchEntry} are repo-derived and are P9-neutralized before storing
     * ({@code SOURCE_FILE_CAP}). The {@code current} flag and the {@code ahead}/{@code behind}
     * counts are server-controlled and need no neutralization. The envelope is marked
     * {@code untrusted=true}.</p>
     *
     * @param verb    the verb name ({@code "git_branch"})
     * @param entries the normalized branch list
     * @return the git-branch envelope
     */
    public static Envelope gitBranch(String verb, List<GitBranchEntry> entries) {
        List<GitBranchEntry> neutralized = entries.stream()
                .map(Envelope::neutralizeGitBranchEntry)
                .toList();
        return new Envelope(true, verb, null, null, null, null, null, null, null, null, null,
                List.copyOf(neutralized), null, null, null, true, null, null);
    }

    /**
     * Build a minimal success envelope ({@code ok=true}) for the {@code install} verb (PRD-3,
     * slice 3). Returns {@code installSummary:{added:N, removed:M, changed:K}} and no
     * {@code failures[]}. Server-authored content only; marked {@code untrusted=false}.
     *
     * @param verb           the verb name ({@code "install"})
     * @param manager        the manager name ({@code "npm"})
     * @param installSummary the parsed added/removed/changed counts from npm stdout
     * @param handle         a handle to the stashed raw npm output; may be null
     * @return the install-success envelope
     */
    public static Envelope installSuccess(String verb, String manager, InstallSummary installSummary,
                                          @Nullable Handle handle) {
        return new Envelope(true, verb, manager, null, null, null, null, null, null, null, null, null,
                installSummary, null, handle, false, null, null);
    }

    /**
     * Build an operational-error envelope ({@code ok=false}) for a verb, with no stashed output.
     * Server-authored content only; marked {@code untrusted=false}.
     */
    public static Envelope operationalError(String verb, ErrorCode code, String message, String hint) {
        return operationalError(verb, code, message, hint, null);
    }

    /**
     * Build an operational-error envelope ({@code ok=false}) carrying a {@link Handle} to the
     * stashed raw output (e.g. the compiler diagnostics behind {@code REPORT_NOT_PRODUCED}).
     * Server-authored content only; marked {@code untrusted=false}.
     */
    public static Envelope operationalError(String verb, ErrorCode code, String message, String hint,
                                            @Nullable Handle handle) {
        return new Envelope(false, verb, null, null, null, null, null, null, null, null, null, null, null, new OperationalError(code, message, hint), handle, false, null, null);
    }

    /**
     * Build a {@code pr_checks} result envelope (PRD-6 S1, #99). Carries the per-check {@code prChecks[]}
     * list ({@code name}/{@code conclusion}/{@code handle}) and, when not ok, the failing checks as
     * top-level {@code failures[]} {@link ContainerFinding}s (scope {@code RUN}). {@code manager} is
     * null (forge is ecosystem-agnostic).
     *
     * <p><b>Container-aware {@code ok}.</b> {@code ok} is computed by the use-case's classifier: one
     * red check-run OR a red commit status OR an incomplete (queued/in_progress) check flips it false.
     * The failure floor never fabricates a finding — {@code failures[]} is exactly the failing set the
     * caller passes.</p>
     *
     * <p><b>P9 neutralization.</b> Check names ({@code prChecks[].name}) and every repo-derived field
     * of each {@link Finding} are attacker-controllable, so both are passed through
     * {@link OutboundNeutralizer} and the envelope is marked {@code untrusted=true}.</p>
     *
     * @param verb     the verb name ({@code "pr_checks"})
     * @param ok       the container-aware verdict (all checks passing)
     * @param checks   the per-check list (all checks; passing and failing)
     * @param failures the failing checks as {@code ContainerFinding(RUN)}; empty when ok
     * @return the pr_checks envelope
     */
    public static Envelope prChecks(String verb, boolean ok, List<PrCheck> checks,
                                    List<Finding> failures) {
        List<PrCheck> neutralizedChecks = checks.stream()
                .map(Envelope::neutralizePrCheck)
                .toList();
        List<Finding> neutralizedFailures = failures.stream()
                .map(Envelope::neutralizeFinding)
                .toList();
        return new Envelope(ok, verb, null, null, List.copyOf(neutralizedFailures), null, null, null,
                null, null, null, null, null, null, null, true, List.copyOf(neutralizedChecks), null);
    }

    /**
     * Build a {@code pr_view} result envelope ({@code ok=true} — a fetched PR view is always
     * "operation succeeded"; the PR's own state/mergeability is data, not an operational failure)
     * (PRD-6 S2, #100). {@code manager} is null (forge is ecosystem-agnostic).
     *
     * <p><b>P9 neutralization.</b> {@code headRef}/{@code baseRef} are repo-derived (a repo can name
     * a branch with adversarial bytes), so they are passed through {@link OutboundNeutralizer} and
     * the envelope is marked {@code untrusted=true}. {@code state}/{@code mergeable}/{@code merged}/
     * {@code headSha}/{@code reviewStatus}/{@code checksSummary} are server-controlled/computed and
     * are not neutralized.</p>
     *
     * @param verb the verb name ({@code "pr_view"})
     * @param view the fetched PR metadata carrier
     * @return the pr_view envelope
     */
    public static Envelope prView(String verb, PrView view) {
        return new Envelope(true, verb, null, null, null, null, null, null, null, null, null, null,
                null, null, null, true, null, neutralizePrView(view));
    }

    /**
     * Build a {@code pr_diff} result envelope ({@code ok=true}) carrying ONLY a {@code handle}
     * (PRD-6 S2, #100). The full unified diff text is stashed by the use-case (mirroring
     * {@code git_diff}'s full-patch handle) and is retrievable, non-lossily, via
     * {@code get_log(handle)}. No repo-derived content is inline in the envelope itself, so it is
     * marked {@code untrusted=false} (mirrors {@link #buildSuccess}, another handle-only success).
     *
     * @param verb   the verb name ({@code "pr_diff"})
     * @param handle the handle pointing at the stashed full diff text
     * @return the pr_diff envelope
     */
    public static Envelope prDiff(String verb, Handle handle) {
        return new Envelope(true, verb, null, null, null, null, null, null, null, null, null, null,
                null, null, handle, false, null, null);
    }

    // ---- P9 neutralization helpers ----

    /**
     * Apply {@link OutboundNeutralizer} to the repo-derived {@code name} of a {@link PrCheck}
     * ({@code CONTAINER_CAP}). The {@code conclusion} is a server-normalized value and the
     * {@code handle} is a server-minted id — neither is neutralized.
     */
    private static PrCheck neutralizePrCheck(PrCheck check) {
        String name = OutboundNeutralizer.neutralize(check.name(), OutboundNeutralizer.CONTAINER_CAP);
        return new PrCheck(name, check.conclusion(), check.handle());
    }

    /**
     * Apply {@link OutboundNeutralizer} to the repo-derived {@code headRef}/{@code baseRef} of a
     * {@link PrView} ({@code SOURCE_FILE_CAP}, branch-name shaped). {@code state}, {@code mergeable},
     * {@code merged}, {@code headSha} (git-generated), {@code reviewStatus}, and
     * {@link PrChecksSummary} are server-controlled/computed and are not neutralized.
     */
    private static PrView neutralizePrView(PrView view) {
        String headRef = OutboundNeutralizer.neutralize(view.headRef(), OutboundNeutralizer.SOURCE_FILE_CAP);
        String baseRef = OutboundNeutralizer.neutralize(view.baseRef(), OutboundNeutralizer.SOURCE_FILE_CAP);
        return new PrView(view.state(), view.mergeable(), view.merged(), headRef, view.headSha(),
                baseRef, view.reviewStatus(), view.checksSummary());
    }

    /**
     * Apply {@link OutboundNeutralizer} to all repo-derived string fields of a {@link Finding}.
     * Returns the same finding object if no field is changed (null-safe, no allocation).
     */
    private static Finding neutralizeFinding(Finding finding) {
        return switch (finding) {
            case TestFinding tf -> neutralizeTestFinding(tf);
            case ContainerFinding cf -> neutralizeContainerFinding(cf);
        };
    }

    private static TestFinding neutralizeTestFinding(TestFinding tf) {
        String suite   = OutboundNeutralizer.neutralize(tf.suite(),   OutboundNeutralizer.SUITE_CAP);
        String name    = OutboundNeutralizer.neutralize(tf.name(),    OutboundNeutralizer.TEST_NAME_CAP);
        String message = OutboundNeutralizer.neutralize(tf.message(), OutboundNeutralizer.MESSAGE_CAP);
        String detail  = OutboundNeutralizer.neutralize(tf.detail(),  OutboundNeutralizer.DETAIL_CAP);
        SourceRef src  = neutralizeSourceRef(tf.source());
        // path[] contains fully qualified class/method names — same cap as name.
        List<String> path = tf.path().stream()
                .map(p -> OutboundNeutralizer.neutralize(p, OutboundNeutralizer.TEST_NAME_CAP))
                .toList();
        return new TestFinding(suite, name, path, tf.outcome(), tf.rawStatus(), message, src, detail);
    }

    private static ContainerFinding neutralizeContainerFinding(ContainerFinding cf) {
        String container = OutboundNeutralizer.neutralize(cf.container(), OutboundNeutralizer.CONTAINER_CAP);
        String message   = OutboundNeutralizer.neutralize(cf.message(),   OutboundNeutralizer.MESSAGE_CAP);
        String detail    = OutboundNeutralizer.neutralize(cf.detail(),    OutboundNeutralizer.DETAIL_CAP);
        SourceRef src    = neutralizeSourceRef(cf.source());
        return new ContainerFinding(cf.scope(), container, cf.outcome(), cf.rawStatus(), message, src, detail);
    }

    private static @Nullable SourceRef neutralizeSourceRef(@Nullable SourceRef src) {
        if (src == null) return null;
        String file = OutboundNeutralizer.neutralize(src.file(), OutboundNeutralizer.SOURCE_FILE_CAP);
        return new SourceRef(file, src.line());
    }

    /**
     * Apply {@link OutboundNeutralizer} to all repo-derived string fields of a
     * {@link CompileDiagnostic}. The {@code file} and {@code message} fields are untrusted
     * compiler output; {@code severity} is a server-controlled value and is not neutralized.
     */
    private static CompileDiagnostic neutralizeDiagnostic(CompileDiagnostic d) {
        String file    = OutboundNeutralizer.neutralize(d.file(),    OutboundNeutralizer.SOURCE_FILE_CAP);
        String message = OutboundNeutralizer.neutralize(d.message(), OutboundNeutralizer.MESSAGE_CAP);
        return new CompileDiagnostic(file, d.line(), d.col(), d.severity(), message);
    }

    /**
     * Apply {@link OutboundNeutralizer} to all repo-derived string fields of a {@link GitStatus}:
     * the branch name, the upstream, and every changed-path entry's {@code path}/{@code origPath}.
     * The {@code code} field is a server-controlled porcelain status code and is not neutralized;
     * the ahead/behind/detached fields are numeric/boolean and need no neutralization.
     * Path-shaped strings use {@code SOURCE_FILE_CAP}.
     */
    private static GitStatus neutralizeGitStatus(GitStatus status) {
        String branch   = OutboundNeutralizer.neutralize(status.branch(),   OutboundNeutralizer.SOURCE_FILE_CAP);
        String upstream = OutboundNeutralizer.neutralize(status.upstream(), OutboundNeutralizer.SOURCE_FILE_CAP);
        return new GitStatus(
                branch,
                status.detached(),
                upstream,
                status.ahead(),
                status.behind(),
                status.staged().stream().map(Envelope::neutralizeGitEntry).toList(),
                status.unstaged().stream().map(Envelope::neutralizeGitEntry).toList(),
                status.untracked().stream().map(Envelope::neutralizeGitEntry).toList());
    }

    private static GitStatusEntry neutralizeGitEntry(GitStatusEntry entry) {
        String path     = OutboundNeutralizer.neutralize(entry.path(),     OutboundNeutralizer.SOURCE_FILE_CAP);
        String origPath = OutboundNeutralizer.neutralize(entry.origPath(), OutboundNeutralizer.SOURCE_FILE_CAP);
        return new GitStatusEntry(path, entry.code(), origPath);
    }

    /**
     * Apply {@link OutboundNeutralizer} to the repo-derived fields of a {@link GitCommit}:
     * {@code author} (MESSAGE_CAP) and {@code subject} (MESSAGE_CAP). The sha/abbrev/dateIso
     * fields are git-generated and safe; they are not neutralized.
     */
    private static GitCommit neutralizeGitCommit(GitCommit commit) {
        String author  = OutboundNeutralizer.neutralize(commit.author(),  OutboundNeutralizer.MESSAGE_CAP);
        String subject = OutboundNeutralizer.neutralize(commit.subject(), OutboundNeutralizer.MESSAGE_CAP);
        return new GitCommit(commit.sha(), commit.abbrev(), author, commit.dateIso(), subject);
    }

    /**
     * Apply {@link OutboundNeutralizer} to the repo-derived fields of a {@link GitCommitDetail}:
     * {@code author} (MESSAGE_CAP), {@code subject} (MESSAGE_CAP), and {@code body} (DETAIL_CAP).
     * The sha/abbrev/dateIso fields are git-generated and safe; they are not neutralized.
     */
    private static GitCommitDetail neutralizeGitCommitDetail(GitCommitDetail detail) {
        String author  = OutboundNeutralizer.neutralize(detail.author(),  OutboundNeutralizer.MESSAGE_CAP);
        String subject = OutboundNeutralizer.neutralize(detail.subject(), OutboundNeutralizer.MESSAGE_CAP);
        String body    = OutboundNeutralizer.neutralize(detail.body(),    OutboundNeutralizer.DETAIL_CAP);
        return new GitCommitDetail(detail.sha(), detail.abbrev(), author, detail.dateIso(), subject, body);
    }

    /**
     * Apply {@link OutboundNeutralizer} to the repo-derived fields of a {@link GitDiffEntry}:
     * {@code path} ({@code SOURCE_FILE_CAP}). The {@code status} letter is a server-controlled
     * value and is not neutralized. The {@code added}/{@code deleted} counts are integers and
     * need no neutralization.
     */
    private static GitDiffEntry neutralizeGitDiffEntry(GitDiffEntry entry) {
        String path = OutboundNeutralizer.neutralize(entry.path(), OutboundNeutralizer.SOURCE_FILE_CAP);
        return new GitDiffEntry(path, entry.added(), entry.deleted(), entry.status());
    }

    /**
     * Apply {@link OutboundNeutralizer} to the repo-derived fields of a {@link GitBranchEntry}:
     * {@code name} and {@code upstream} ({@code SOURCE_FILE_CAP}). The {@code current} boolean
     * and the {@code ahead}/{@code behind} integers are server-controlled and need no
     * neutralization.
     */
    private static GitBranchEntry neutralizeGitBranchEntry(GitBranchEntry entry) {
        String name     = OutboundNeutralizer.neutralize(entry.name(),     OutboundNeutralizer.SOURCE_FILE_CAP);
        String upstream = OutboundNeutralizer.neutralize(entry.upstream(), OutboundNeutralizer.SOURCE_FILE_CAP);
        return new GitBranchEntry(name, entry.current(), upstream, entry.ahead(), entry.behind());
    }
}
