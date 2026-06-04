package dev.nobash;

import io.micronaut.mcp.annotations.Tool;
import io.micronaut.mcp.annotations.ToolArg;
import jakarta.inject.Singleton;

/**
 * Trivial @Tool bean for the STDIO spike — proves a tool is discovered via DI and
 * callable end-to-end over the JSON-RPC/STDIO transport. Mirrors the production
 * pattern (DESIGN.md §7): a @Tool method on a @Singleton bean, structured output via
 * a @JsonSchema record.
 */
@Singleton
public class PingTool {

    @Tool(description = "Echo a message back — proves a tool is registered over STDIO")
    public PingResult ping(@ToolArg(name = "message", description = "text to echo back") String message) {
        return new PingResult("pong: " + message, message.length());
    }
}
