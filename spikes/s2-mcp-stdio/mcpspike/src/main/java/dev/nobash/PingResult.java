package dev.nobash;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.jsonschema.JsonSchema;
import io.micronaut.serde.annotation.Serdeable;

/**
 * Structured tool output. Reflection-free for native: @Serdeable (compile-time
 * serializers), @Introspected, @JsonSchema (compile-time schema). DESIGN.md §7.
 */
@Serdeable
@Introspected
@JsonSchema
public record PingResult(String reply, int length) {}
