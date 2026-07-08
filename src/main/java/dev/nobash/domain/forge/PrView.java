package dev.nobash.domain.forge;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * The {@code pr_view} one-call metadata envelope carrier (PRD-6 S2, #100): PR state, mergeability,
 * a folded review status, head/base refs, and a checks summary, gathered from several internal REST
 * GETs (the pulls resource, its reviews, and the head-sha checks fold reused from {@code pr_checks})
 * so the AGENT sees exactly one call.
 *
 * <p><b>Review-status folding</b> is deliberately simple (YAGNI beyond a stub-test level): any review
 * in {@code CHANGES_REQUESTED} state → {@code "changes_requested"}; else any {@code APPROVED} →
 * {@code "approved"}; else at least one review present → {@code "reviewed"}; else {@code "none"}. Not
 * a full per-reviewer latest-state reduction — the GitHub UI's algorithm is considerably richer.</p>
 *
 * <p>{@code headRef} and {@code baseRef} are repo-derived (a repo can name a branch with adversarial
 * bytes), so they are P9-neutralized when the envelope is built and the envelope is marked
 * {@code untrusted=true}. {@code headSha} is a git-generated hex SHA and safe. {@code state},
 * {@code mergeable}, {@code merged}, and {@code reviewStatus} are server-controlled/computed values.</p>
 *
 * @param state         the PR state ({@code open}/{@code closed})
 * @param mergeable     GitHub's mergeability flag; {@code null} while GitHub is still computing it
 * @param merged        whether the PR has been merged
 * @param headRef       the head branch name (repo-derived, neutralized)
 * @param headSha       the head commit SHA (git-generated, safe)
 * @param baseRef       the base branch name (repo-derived, neutralized)
 * @param reviewStatus  the folded review status ({@code approved}/{@code changes_requested}/
 *                      {@code reviewed}/{@code none})
 * @param checksSummary the folded CI-checks summary (reused from the {@code pr_checks} classifier)
 */
@Serdeable
@Introspected
public record PrView(String state, @Nullable Boolean mergeable, boolean merged,
                     String headRef, String headSha, String baseRef,
                     String reviewStatus, PrChecksSummary checksSummary) {
}
