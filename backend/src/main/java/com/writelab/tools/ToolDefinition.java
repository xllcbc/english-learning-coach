package com.writelab.tools;

/**
 * A tool that can be shared by agent turns and workflows. A definition is stateless; the state a
 * single caller must keep (for example the number of lookups already spent) lives in the
 * {@link ToolSession} returned by {@link #openSession()}.
 */
public interface ToolDefinition {

    String name();

    String description();

    ToolSession openSession();

    /**
     * Opens a session that participates in one agent turn's citation numbering. Tools that never
     * produce citable material keep the default; retrieval and dictionary tools override it.
     */
    default ToolSession openSession(ToolTurnContext context) {
        return openSession();
    }
}
