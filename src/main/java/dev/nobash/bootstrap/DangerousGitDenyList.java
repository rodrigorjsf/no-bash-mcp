package dev.nobash.bootstrap;

import java.util.List;
import java.util.Set;

/**
 * The versioned <b>transitional dangerous-git deny-list</b> data artifact (decision-log D35/D16,
 * {@code docs/design/bootstrap-and-deployment.md}). It is the <b>single source</b> feeding both the
 * {@link HarnessConfigWriter} (which emits these patterns into the harness {@code permissions.deny}
 * array) and the data assertion that guards the set — so the writer and the assertion cannot drift.
 *
 * <h3>Posture — "block destruction only" (D35)</h3>
 * <p>The deny-list <b>denies</b> irreversible / history-rewriting / working-tree-loss git operations
 * and <b>allows</b> forward-progress git, so the agent keeps its {@code edit → commit → push} loop
 * (required for dogfooding-on-self) but cannot <em>destroy</em> the repository. This is an explicitly
 * <b>transitional bridge</b>: the real guarantee arrives when mutating-git becomes allowlist MCP
 * verbs (post-v1 roadmap). The known G6 fragility — a denylist cannot catch
 * {@code git -c <config-injection>}, aliases, or command-chaining — is <b>accepted</b>, not hardened
 * (D35).</p>
 *
 * <h3>Rule-string format</h3>
 * <p>Each deny entry is a <b>Claude Code permission rule string</b> in the documented
 * {@code Bash(<command>:*)} grammar: the {@code :*} suffix matches the command and any trailing
 * arguments. This is a <b>declarative permission-config entry</b> evaluated by the harness — NOT a
 * {@code PreToolUse} bash hook (D16: a hook is bash, breaking cross-OS, and is regex-on-string,
 * the G7 minefield).</p>
 *
 * <p>Placement note: this class sits in the top-level {@code dev.nobash.bootstrap} package, OUTSIDE
 * the Domain/Application/Adapter triad. The harness permission-config writer is a SEPARATE
 * deliverable from the MCP server and implements neither outbound port (DESIGN §3/§8) — so it must
 * not live under {@code adapter/out/harness} (forbidden by name) nor {@code application/verb/*}
 * (the ArchUnit slices rule). It depends only on the JDK; it imports nothing from the triad.</p>
 */
public final class DangerousGitDenyList {

    private DangerousGitDenyList() {
    }

    /**
     * The data-artifact version. Bumped whenever the {@link #DENY} set changes, so a harness config
     * can record which deny-list generation it was written from.
     */
    public static final String VERSION = "1";

    /**
     * The D35 "block destruction only" DENY set, as Claude Code {@code Bash(<command>:*)} rule
     * strings. Every entry denies an irreversible / history-rewriting / working-tree-loss git op.
     *
     * <p>Force/delete push is expressed as the specific dangerous forms (never a bare
     * {@code git push}, which is forward-progress and must stay allowed). The list is intentionally
     * exhaustive of the D35 enumeration; it is NOT hardened against config-injection/aliases (G6,
     * accepted).</p>
     *
     * <p><b>Accepted G6 gap — the bare colon-refspec delete ({@code git push origin :ref}).</b> The
     * D35 enumeration lists {@code push :ref} as a delete form, but it is <b>inexpressible</b> as a
     * Claude Code {@code Bash(<prefix>:*)} prefix rule: it shares its entire textual prefix
     * ({@code git push origin }) with plain {@code git push origin main}, so any prefix rule broad
     * enough to catch {@code :ref} would <b>over-block plain push</b> — and not over-blocking
     * forward-progress push is the non-negotiable D35 thesis (keep the {@code edit → commit → push}
     * loop). The <b>intent</b> is already denied: {@code git push origin :ref} is exactly equivalent
     * to {@code git push origin --delete ref}, which IS on the list. Only the alternate spelling
     * leaks — squarely within the G6 fragility D35 explicitly accepts (a denylist cannot catch every
     * alias/spelling; the real guarantee arrives with post-v1 allowlist mutating-git verbs).</p>
     */
    private static final List<String> DENY = List.of(
            // ---- force / delete push (NEVER plain `git push`) ----
            "Bash(git push --force:*)",
            "Bash(git push -f:*)",
            "Bash(git push --force-with-lease:*)",
            "Bash(git push --delete:*)",
            "Bash(git push origin --delete:*)",
            // ---- history rewriting / reset ----
            "Bash(git reset --hard:*)",
            "Bash(git rebase:*)",
            "Bash(git commit --amend:*)",
            "Bash(git filter-branch:*)",
            "Bash(git filter-repo:*)",
            // ---- working-tree / index destruction ----
            "Bash(git clean -f:*)",
            "Bash(git clean -fd:*)",
            "Bash(git clean -fdx:*)",
            "Bash(git checkout --:*)",             // `git checkout -- <path>` discards worktree edits
            "Bash(git restore --:*)",              // `git restore -- <path>` discards worktree edits
            "Bash(git checkout -f:*)",
            // ---- ref / branch / tag deletion ----
            "Bash(git branch -D:*)",
            "Bash(git branch -d:*)",
            "Bash(git tag -d:*)",
            "Bash(git update-ref -d:*)",
            // ---- stash / reflog / gc destruction ----
            "Bash(git stash drop:*)",
            "Bash(git stash clear:*)",
            "Bash(git reflog expire:*)",
            "Bash(git gc --prune=now:*)",
            "Bash(git prune:*)",
            // ---- worktree / submodule removal ----
            "Bash(git worktree remove -f:*)",
            "Bash(git worktree remove --force:*)",
            "Bash(git submodule deinit:*)");

    /**
     * Representative <b>forward-progress</b> commands the deny-list must NEVER match (D35 ALLOW set).
     * Used by the data assertion to prove no deny rule over-blocks a benign command. These are real
     * command strings (the kind the agent would run), not rule strings.
     */
    private static final List<String> ALLOWED_REPRESENTATIVES = List.of(
            "git add -A",
            "git add src/Foo.java",
            "git commit -m \"msg\"",
            "git push",                            // plain push — the canary: a substring matcher
            "git push origin main",                //   would wrongly flag this (push ⊂ push --force)
            "git branch feature-x",                // create a new branch (no -d/-D)
            "git switch main",
            "git checkout main",                   // switch to a branch (no `--`, no -f)
            "git restore --staged Foo.java",       // unstage is forward-progress (no `--` path discard)
            "git merge --ff-only main",
            "git fetch origin",
            "git pull");

    /**
     * @return the deny-list rule strings (immutable). The single source the writer serializes into
     *         {@code permissions.deny} and the data assertion checks for completeness.
     */
    public static List<String> denyRules() {
        return DENY;
    }

    /**
     * @return the representative forward-progress commands that must remain allowed (immutable).
     */
    public static List<String> allowedRepresentatives() {
        return ALLOWED_REPRESENTATIVES;
    }

    /**
     * Whether a Claude Code {@code Bash(<prefix>:*)} deny rule matches a concrete command string,
     * using <b>token/prefix</b> semantics (NOT substring containment).
     *
     * <p>A substring match would false-positive: {@code "push"} is a substring of
     * {@code "push --force"}, so a substring matcher would flag plain {@code git push} (a
     * forward-progress op) against the {@code git push --force} rule. The prefix-with-boundary
     * semantics here mirror how Claude Code's {@code Bash(<cmd>:*)} prefix rules actually evaluate:
     * the rule matches a command iff the command <em>is</em> the prefix or <em>starts with the
     * prefix followed by a space</em> (a token boundary).</p>
     *
     * @param rule    a deny rule string in {@code Bash(<prefix>:*)} form
     * @param command a concrete command string (e.g. {@code "git push --force origin main"})
     * @return {@code true} iff the rule's command prefix matches {@code command} at a token boundary
     */
    public static boolean matches(String rule, String command) {
        String prefix = extractPrefix(rule);
        if (prefix == null) {
            return false;
        }
        String cmd = command.strip();
        return cmd.equals(prefix) || cmd.startsWith(prefix + " ");
    }

    /**
     * Extract the command prefix from a {@code Bash(<prefix>:*)} rule string. Returns {@code null}
     * for a rule that is not in the expected form.
     */
    static String extractPrefix(String rule) {
        if (rule == null || !rule.startsWith("Bash(") || !rule.endsWith(":*)")) {
            return null;
        }
        // Strip the leading "Bash(" and the trailing ":*)".
        return rule.substring("Bash(".length(), rule.length() - ":*)".length()).strip();
    }

    /**
     * @return the distinct command prefixes of every deny rule (for assertion convenience).
     */
    public static Set<String> denyPrefixes() {
        return DENY.stream()
                   .map(DangerousGitDenyList::extractPrefix)
                   .filter(p -> p != null)
                   .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
