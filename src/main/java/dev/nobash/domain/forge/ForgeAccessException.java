package dev.nobash.domain.forge;

import dev.nobash.domain.error.ErrorCode;
import io.micronaut.core.annotation.Nullable;

/**
 * A typed failure of a forge REST access, thrown by the outbound {@code ForgePort} adapter and
 * caught by the {@code pr_checks} use-case, which maps it to a structured operational-error Envelope
 * (PRD-6 S1, #99). It carries the {@link ErrorCode} the agent branches on, an operator/agent hint,
 * and — for {@link ErrorCode#FORGE_RATE_LIMITED} — the surfaced {@code Retry-After} value (never
 * auto-retried, D65).
 *
 * <p>Throwing a typed exception across the port (and catching it in the use-case) keeps the port
 * signature clean while honoring "never let an unstructured exception escape the Envelope contract":
 * the use-case's single catch maps every case deterministically. The message/hint/retry-after carry
 * NO secret — the token is never included (forge-security-model.md area 1).</p>
 */
public class ForgeAccessException extends RuntimeException {

    private final ErrorCode code;
    private final String hint;
    private final String retryAfter;

    public ForgeAccessException(ErrorCode code, String message, String hint) {
        this(code, message, hint, null);
    }

    public ForgeAccessException(ErrorCode code, String message, String hint, @Nullable String retryAfter) {
        super(message);
        this.code = code;
        this.hint = hint;
        this.retryAfter = retryAfter;
    }

    /** The operational error code the agent branches on. */
    public ErrorCode code() {
        return code;
    }

    /** A remediation hint for the operator/agent. */
    public String hint() {
        return hint;
    }

    /** The surfaced {@code Retry-After} value for a rate-limit failure; null otherwise. */
    @Nullable
    public String retryAfter() {
        return retryAfter;
    }
}
