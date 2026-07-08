package dev.nobash.domain.forge;

/**
 * A parsed {@code origin} git remote: the forge {@code host} plus the {@code owner}/{@code repo}
 * slug (PRD-6 S1, #99, D62(5)). Produced by {@link OriginRemoteParser} from the SSH or HTTPS remote
 * URL; the host is what the allowlist match keys on (SSRF floor — the host is never agent-supplied),
 * and the slug identifies the repository for the REST path.
 *
 * @param host  the forge host (e.g. {@code github.com}); never null/blank once parsed
 * @param owner the repository owner (org or user)
 * @param repo  the repository name (with any trailing {@code .git} stripped)
 */
public record OriginRemote(String host, String owner, String repo) {

    /** The {@code owner/repo} slug for the REST path. */
    public String slug() {
        return owner + "/" + repo;
    }
}
