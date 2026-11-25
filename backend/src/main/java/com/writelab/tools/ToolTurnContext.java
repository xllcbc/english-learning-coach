package com.writelab.tools;

/**
 * Turn-scoped context shared by every tool session opened for one agent turn. The reference
 * allocator is the only state: it keeps citations from different tools in one numbering, while each
 * tool still keeps its own per-turn execution budget in its session.
 */
public record ToolTurnContext(ToolReferenceAllocator references) {

    public ToolTurnContext {
        references = references == null ? new ToolReferenceAllocator(1) : references;
    }

    public ToolTurnContext(int firstReference) {
        this(new ToolReferenceAllocator(firstReference));
    }

    public static ToolTurnContext startingAt(int firstReference) {
        return new ToolTurnContext(new ToolReferenceAllocator(firstReference));
    }

    public static ToolTurnContext empty() {
        return startingAt(1);
    }
}
