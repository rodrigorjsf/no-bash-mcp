package dev.nobash.domain.forge;

import java.net.URI;
import java.util.Optional;

/**
 * Pure parser for a git {@code origin} remote URL → {@link OriginRemote} (host + owner/repo slug),
 * covering the two forms git emits (PRD-6 S1, #99, D62(5)):
 *
 * <ul>
 *   <li><b>SSH scp-like</b> — {@code git@github.com:owner/repo.git} (the default GitHub SSH form).</li>
 *   <li><b>SSH scheme</b> — {@code ssh://git@github.com/owner/repo.git}.</li>
 *   <li><b>HTTPS/HTTP</b> — {@code https://github.com/owner/repo(.git)}.</li>
 * </ul>
 *
 * <p>IO-free and stateless: it parses a string already in memory, never touches the filesystem or a
 * process (mirrors the git porcelain-parse discipline). Any unrecognized/blank input returns
 * {@link Optional#empty()} — the use-case maps that to {@code FORGE_ORIGIN_UNRESOLVED}, never a
 * guess. A trailing {@code .git} is stripped; the first two path segments are {@code owner}/{@code
 * repo}.</p>
 */
public final class OriginRemoteParser {

    private OriginRemoteParser() {
        // Utility — no instances.
    }

    /**
     * Parse a remote URL into its host + owner/repo slug.
     *
     * @param remoteUrl the raw {@code git remote get-url origin} output; may be null/blank
     * @return the parsed remote, or empty when the input is blank or not a recognized SSH/HTTPS form
     */
    public static Optional<OriginRemote> parse(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return Optional.empty();
        }
        String url = remoteUrl.strip();

        // SSH scp-like: [user@]host:owner/repo(.git) — no scheme, has '@' and ':' (not "://").
        if (!url.contains("://") && url.contains("@") && url.contains(":")) {
            int at = url.indexOf('@');
            int colon = url.indexOf(':', at);
            String host = url.substring(at + 1, colon);
            String path = url.substring(colon + 1);
            return build(host, path);
        }

        // Scheme-based: ssh:// https:// http:// — parse via URI (handles user-info and port).
        try {
            URI uri = URI.create(url);
            return build(uri.getHost(), uri.getPath());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<OriginRemote> build(String host, String path) {
        if (host == null || host.isBlank() || path == null || path.isBlank()) {
            return Optional.empty();
        }
        String cleaned = path.strip();
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.endsWith(".git")) {
            cleaned = cleaned.substring(0, cleaned.length() - ".git".length());
        }
        String[] segments = cleaned.split("/");
        if (segments.length < 2 || segments[0].isBlank() || segments[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new OriginRemote(host.strip(), segments[0], segments[1]));
    }
}
