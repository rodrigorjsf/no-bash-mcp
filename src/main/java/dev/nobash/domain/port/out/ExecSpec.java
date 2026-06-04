package dev.nobash.domain.port.out;

import java.util.List;

/**
 * The carrier of a command invocation across the outbound execution port. {@code argv} is
 * an explicit, ordered token array — the security keystone (security-model.md, ADR-0008):
 * the manager binary is {@code argv[0]} and every other token is a literal element, NEVER a
 * shell string and NEVER passed to {@code /bin/sh -c}. {@code workingDir} is the resolved
 * path the manager runs in.
 *
 * <p>Minimal by design for this slice: it carries only what the operational-error gate and
 * the argv guarantee need. Result mapping ({@code ExecResult}) and real execution are a
 * later slice.</p>
 */
public record ExecSpec(List<String> argv, String workingDir) {

    public ExecSpec {
        argv = List.copyOf(argv);
    }
}
