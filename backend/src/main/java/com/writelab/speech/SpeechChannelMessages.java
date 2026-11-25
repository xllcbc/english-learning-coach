package com.writelab.speech;

/**
 * Wire shapes for the speech WebSocket. Client and server use JSON text frames so a text fallback
 * can travel over the same connection as audio.
 *
 * <p>Audio request:
 * <pre>{@code
 * {"type":"audio","audio":"<base64>","mimeType":"audio/webm"}
 * }</pre>
 * Text input after a fallback:
 * <pre>{@code
 * {"type":"text","text":"..."}
 * }</pre>
 * Audio reply:
 * <pre>{@code
 * {"type":"audio","audio":"<base64>","mimeType":"audio/wav","transcript":"..."}
 * }</pre>
 * Text degradation:
 * <pre>{@code
 * {"type":"text","text":"...","fallback":true,"reason":"timeout"}
 * }</pre>
 */
public final class SpeechChannelMessages {

    public static final String TYPE_AUDIO = "audio";
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_ERROR = "error";

    private SpeechChannelMessages() {
    }

    public record SpeechInput(String type, String audio, String mimeType, String text) {
    }

    public sealed interface OutgoingMessage
            permits SpokenReplyMessage, TextReplyMessage, ErrorMessage {
    }

    public record SpokenReplyMessage(String type, String audio, String mimeType, String transcript)
            implements OutgoingMessage {
    }

    public record TextReplyMessage(String type, String text, boolean fallback, String reason)
            implements OutgoingMessage {
    }

    public record ErrorMessage(String type, String code, String message)
            implements OutgoingMessage {
    }
}
