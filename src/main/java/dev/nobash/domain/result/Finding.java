package dev.nobash.domain.result;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A normalized finding (ADR-0007). SEALED so the no-test-owner case (axis 5) is a
 * first-class {@link ContainerFinding} type, never a degenerate {@link TestFinding} with
 * empty fields. The common accessors carry the normalized {@link Outcome}, the retained
 * raw status, and the best-effort signal ({@code message}, {@code source}, {@code detail}).
 *
 * <p>Field names are frozen (ADR-0007): {@code outcome}, {@code rawStatus}, {@code message},
 * {@code source}, {@code detail}.</p>
 *
 * <p>Slice #4 (D30) adds the polymorphic serialization contract owed by ADR-0007 / DESIGN §7:
 * {@code @JsonTypeInfo(use=NAME, property="kind")} + {@code @JsonSubTypes} emit a stable
 * {@code "kind"} discriminator ({@code "test"} / {@code "container"}) so the agent branches
 * {@link TestFinding} vs {@link ContainerFinding} on it (the established agent-facing
 * {@code failures[]} shape). {@code defaultImpl} satisfies {@code subtypes-require-default-impl}
 * (default {@code true}) on read-back. micronaut-serde 3.0.0 honors this subset of Jackson
 * annotations — proven by a JVM round-trip ({@code FindingSerdeTest}).</p>
 */
@Serdeable
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind",
        defaultImpl = TestFinding.class)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TestFinding.class, name = "test"),
        @JsonSubTypes.Type(value = ContainerFinding.class, name = "container")
})
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
