package com.writelab.tools;

import java.util.Map;

/**
 * One caller's view of a registered tool. Sessions are opened per turn so a per-turn bound, such
 * as the two automatic {@code lookup_word} calls, is not shared across turns.
 */
@FunctionalInterface
public interface ToolSession {

    ToolResult invoke(Map<String, Object> arguments);
}
