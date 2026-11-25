package com.writelab.tools;

import java.util.Map;

/**
 * Small argument accessors shared by model-driven tools. Models sometimes put the only argument
 * under {@code value} instead of the documented name; accepting both keeps one malformed call from
 * failing a whole turn.
 */
final class ToolArguments {

    private ToolArguments() {
    }

    static String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            value = arguments.get("value");
        }
        return value == null ? "" : value.toString().strip();
    }
}
