package com.writelab.speech;

/**
 * Wire shapes for the speech WebSocket. Client and server use JSON text frames so a text fallback
 * can travel over the same connection as audio.
 *
 * <p>Audio request with an optional session id ({@code sessionId} is absent on the first turn):
 * <pre>{@code
 * {"type":"audio","audio":"<base64>","mimeType":"audio/webm","sessionId":"<uuid>"}
 * }</pre>
 * Text input after a fallback, on the same session:
 * <pre>{@code
 * {"type":"text","text":"...","sessionId":"<uuid>"}
 * }</pre>
 * Audio reply:
 * <pre>{@code
 * {"type":"audio","sessionId":"<uuid>","audio":"<base64>","mimeType":"audio/wav",
 *  "transcript":"...","reply":"..."}
 * }</pre>
 * Text degradation:
 * <pre>{@code
 * {"type":"text","sessionId":"<uuid>","text":"...","fallback":true,"reason":"timeout",
 *  "transcript":"..."}
 * }</pre>
 * Barge-in: the learner started speaking, so the channel cancels the reply it is still producing.
 * The scope is the connection, not a session, so no session id is needed. The channel acknowledges
 * the cancellation with an {@code interrupted} message:
 * <pre>{@code
 * {"type":"barge_in"}
 * {"type":"interrupted","reason":"barge_in"}
 * }</pre>
 */
public final class SpeechChannelMessages {

    public static final String TYPE_AUDIO = "audio";
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_BARGE_IN = "barge_in";
    public static final String TYPE_INTERRUPTED = "interrupted";
    public static final String TYPE_ERROR = "error";
    public static final String BARGE_IN_REASON = "barge_in";

    private SpeechChannelMessages() {
    }

    public record SpeechInput(String type, String audio, String mimeType, String text, String sessionId) {
    }

    public sealed interface OutgoingMessage
            permits SpokenReplyMessage, TextReplyMessage, InterruptedMessage, ErrorMessage {
    }

    public record SpokenReplyMessage(
            String type,
            String sessionId,
            String audio,
            String mimeType,
            String transcript,
            String reply,
            String inputMode,
            boolean mockMode,
            Integer latencyMs
    ) implements OutgoingMessage {
    }

    public record TextReplyMessage(
            String type,
            String sessionId,
            String text,
            boolean fallback,
            String reason,
            String transcript,
            String inputMode,
            boolean mockMode,
            Integer latencyMs
    ) implements OutgoingMessage {
    }

    public record InterruptedMessage(String type, String reason)
            implements OutgoingMessage {
    }

    public record ErrorMessage(String type, String code, String message)
            implements OutgoingMessage {
    }
}
