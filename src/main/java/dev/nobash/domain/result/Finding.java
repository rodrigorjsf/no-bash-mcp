package dev.nobash.domain.result;

/**
 * A normalized finding (ADR-0007). SEALED so the no-test-owner case (axis 5) is a
 * first-class {@link ContainerFinding} type, never a degenerate {@link TestFinding} with
 * empty fields. The common accessors carry the normalized {@link Outcome}, the retained
 * raw status, and the best-effort signal ({@code message}, {@code source}, {@code detail}).
 *
 * <p>Field names are frozen (ADR-0007): {@code outcome}, {@code rawStatus}, {@code message},
 * {@code source}, {@code detail}. Native serialization of this polymorphic graph
 * ({@code @JsonTypeInfo}/{@code defaultImpl}) is a later-slice obligation (DESIGN §7) and is
 * deliberately ABSENT here — this slice is the pure, I/O-free domain core only.</p>
 */
public sealed interface Finding permits TestFinding, ContainerFinding {

    /** The normalized outcome. */
    Outcome outcome();

    /** The raw, format-specific status retained verbatim (e.g. {@code "failure"}, {@code "error"}). */
    String rawStatus();

    /** A best-effort human-readable message; may be null. */
    String message();

    /** A best-effort, derived source location; may be null. */
    SourceRef source();

    /** The full retained detail (e.g. the stack trace text); may be null. */
    String detail();
}
