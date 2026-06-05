package dev.nobash.bootstrap;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data assertion for the {@link DangerousGitDenyList} artifact (AC1, AC3, AC7). It proves two
 * properties of the single-source deny-list, independent of any file I/O:
 *
 * <ol>
 *   <li><b>Completeness (AC1):</b> every D35 "block destruction only" pattern is present.</li>
 *   <li><b>No over-block (AC3/AC7):</b> no deny rule matches a representative forward-progress
 *       command — proven with <b>token/prefix</b> semantics (NOT substring), so plain
 *       {@code git push} stays allowed even though {@code "push"} is a substring of
 *       {@code "push --force"}.</li>
 * </ol>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DangerousGitDenyListTest {

    // ---- AC1: every D35 dangerous pattern is present ----

    @Test
    void the_deny_list_enumerates_every_d35_block_destruction_only_pattern() {
        List<String> prefixes = DangerousGitDenyList.denyRules().stream()
                .map(DangerousGitDenyList::extractPrefix)
                .toList();

        // force / delete push (the specific dangerous forms — never a bare `git push`)
        assertThat(prefixes).contains("git push --force");
        assertThat(prefixes).contains("git push -f");
        assertThat(prefixes).contains("git push --force-with-lease");
        assertThat(prefixes).contains("git push --delete");
        assertThat(prefixes).contains("git push origin --delete");
        // NOTE: the bare colon-refspec delete (`git push origin :ref`) is an ACCEPTED G6 gap — it is
        // inexpressible as a prefix rule without over-blocking plain `git push origin main`, and its
        // intent is already denied via `git push origin --delete` (the equivalent spelling). See the
        // DangerousGitDenyList javadoc and decision-log D35.

        // history rewriting / reset
        assertThat(prefixes).contains("git reset --hard");
        assertThat(prefixes).contains("git rebase");
        assertThat(prefixes).contains("git commit --amend");
        assertThat(prefixes).contains("git filter-branch");
        assertThat(prefixes).contains("git filter-repo");

        // working-tree / index destruction
        assertThat(prefixes).contains("git clean -f");
        assertThat(prefixes).contains("git clean -fd");
        assertThat(prefixes).contains("git clean -fdx");
        assertThat(prefixes).contains("git checkout --");
        assertThat(prefixes).contains("git restore --");
        assertThat(prefixes).contains("git checkout -f");

        // ref / branch / tag deletion
        assertThat(prefixes).contains("git branch -D");
        assertThat(prefixes).contains("git branch -d");
        assertThat(prefixes).contains("git tag -d");
        assertThat(prefixes).contains("git update-ref -d");

        // stash / reflog / gc destruction
        assertThat(prefixes).contains("git stash drop");
        assertThat(prefixes).contains("git stash clear");
        assertThat(prefixes).contains("git reflog expire");
        assertThat(prefixes).contains("git gc --prune=now");
        assertThat(prefixes).contains("git prune");

        // worktree / submodule removal
        assertThat(prefixes).contains("git worktree remove -f");
        assertThat(prefixes).contains("git worktree remove --force");
        assertThat(prefixes).contains("git submodule deinit");
    }

    @Test
    void every_deny_rule_is_a_well_formed_claude_code_bash_prefix_rule() {
        // Each entry must parse as a Bash(<prefix>:*) rule — the declarative permission-config
        // grammar (NOT a PreToolUse bash hook).
        for (String rule : DangerousGitDenyList.denyRules()) {
            assertThat(rule).startsWith("Bash(");
            assertThat(rule).endsWith(":*)");
            assertThat(DangerousGitDenyList.extractPrefix(rule))
                    .as("rule %s must yield a non-blank command prefix", rule)
                    .isNotBlank();
        }
    }

    @Test
    void the_artifact_carries_a_version() {
        assertThat(DangerousGitDenyList.VERSION).isNotBlank();
    }

    // ---- AC3 / AC7: no forward-progress op is matched (token/prefix, NOT substring) ----

    @Test
    void no_deny_rule_matches_any_forward_progress_representative_command() {
        List<String> denyRules = DangerousGitDenyList.denyRules();
        for (String allowed : DangerousGitDenyList.allowedRepresentatives()) {
            for (String rule : denyRules) {
                assertThat(DangerousGitDenyList.matches(rule, allowed))
                        .as("deny rule %s must NOT match forward-progress command '%s'", rule, allowed)
                        .isFalse();
            }
        }
    }

    @Test
    void plain_git_push_is_the_canary_allowed_while_force_push_is_denied() {
        // The discriminator that forces a real (non-substring) matcher: plain `git push` is
        // forward-progress and must be ALLOWED; `git push --force` is destruction and must be
        // DENIED. A substring matcher would wrongly flag plain push (push ⊂ push --force).
        String forcePushRule = denyRuleFor("git push --force");

        assertThat(DangerousGitDenyList.matches(forcePushRule, "git push"))
                .as("plain `git push` must NOT be denied").isFalse();
        assertThat(DangerousGitDenyList.matches(forcePushRule, "git push origin main"))
                .as("plain `git push origin main` must NOT be denied").isFalse();
        assertThat(DangerousGitDenyList.matches(forcePushRule, "git push --force"))
                .as("`git push --force` MUST be denied").isTrue();
        assertThat(DangerousGitDenyList.matches(forcePushRule, "git push --force origin main"))
                .as("`git push --force origin main` MUST be denied").isTrue();
    }

    @Test
    void branch_delete_is_denied_while_branch_create_is_allowed() {
        String branchDeleteRule = denyRuleFor("git branch -D");

        assertThat(DangerousGitDenyList.matches(branchDeleteRule, "git branch -D feature"))
                .as("`git branch -D feature` MUST be denied").isTrue();
        assertThat(DangerousGitDenyList.matches(branchDeleteRule, "git branch feature-x"))
                .as("creating a new branch must NOT be denied").isFalse();
    }

    @Test
    void checkout_path_discard_is_denied_while_checkout_branch_is_allowed() {
        String checkoutPathRule = denyRuleFor("git checkout --");

        assertThat(DangerousGitDenyList.matches(checkoutPathRule, "git checkout -- src/Foo.java"))
                .as("`git checkout -- <path>` MUST be denied").isTrue();
        assertThat(DangerousGitDenyList.matches(checkoutPathRule, "git checkout main"))
                .as("switching to a branch must NOT be denied").isFalse();
    }

    private static String denyRuleFor(String prefix) {
        return DangerousGitDenyList.denyRules().stream()
                .filter(r -> prefix.equals(DangerousGitDenyList.extractPrefix(r)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no deny rule for prefix: " + prefix));
    }
}
