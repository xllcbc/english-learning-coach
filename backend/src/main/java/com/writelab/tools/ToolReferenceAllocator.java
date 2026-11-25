package com.writelab.tools;

/**
 * Allocates the {@code [依据N]} references one agent turn may cite. Briefing sources are numbered
 * before the turn starts; every successful tool that produces citable material reserves the next
 * block, so a lookup and a retrieval cannot hand the model the same number.
 */
public final class ToolReferenceAllocator {

    private int nextReference;

    public ToolReferenceAllocator(int firstReference) {
        this.nextReference = Math.max(1, firstReference);
    }

    /**
     * Reserves {@code count} consecutive references and returns the first one. A non-positive count
     * reserves nothing and returns the next free reference without advancing it.
     */
    public synchronized int reserve(int count) {
        int first = nextReference;
        if (count > 0) {
            nextReference += count;
        }
        return first;
    }
}
