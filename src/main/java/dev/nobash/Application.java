package dev.nobash;

import io.micronaut.runtime.Micronaut;

/**
 * Entry point. {@code banner(false)} keeps stdout pure JSON-RPC for the STDIO transport;
 * {@code logback.xml} routes all logs to stderr (DESIGN.md §7 — the #1 STDIO failure mode).
 */
public final class Application {

    private Application() {
    }

    public static void main(String[] args) {
        Micronaut.build(args)
                 .banner(false)
                 .start();
    }
}
