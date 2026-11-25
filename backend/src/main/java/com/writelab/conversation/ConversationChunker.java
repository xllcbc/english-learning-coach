package com.writelab.conversation;

import java.util.ArrayList;
import java.util.List;

final class ConversationChunker {

    private ConversationChunker() {
    }

    static List<String> chunks(String text) {
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
