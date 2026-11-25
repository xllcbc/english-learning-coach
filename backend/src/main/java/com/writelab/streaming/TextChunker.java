package com.writelab.streaming;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an already-composed answer into SSE-sized chunks at sentence boundaries. Shared by the
 * Teacher Explanation and Teacher Conversation channels so their replay chunking stays identical.
 */
public final class TextChunker {

    private TextChunker() {
    }

    public static List<String> chunks(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }
        for (String chunk : text.split("(?<=[。！？；\\n])")) {
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
        }
        if (chunks.isEmpty()) {
            chunks.add(text);
        }
        return chunks;
    }
}
