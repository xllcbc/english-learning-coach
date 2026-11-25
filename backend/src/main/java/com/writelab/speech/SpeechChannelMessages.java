package com.writelab.speech;

/**
 * Wire shapes for the voice WebSocket. Client and server use JSON text frames so a text fallback
 * can travel over the same connection as audio.
 *
 * <p>Request:
 * <pre>{@code
 * {"type":"audio","audio":"<base64>","mimeType":"audio/webm"}
 * }</pre>
 * Success:
 * <pre>{@code
 * {"type":"audio","audio":"<base64>","mimeType":"audio/wav","transcript":"..."}
 * }</pre>
 * Degradation:
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

    public record SpeechInput(String type, String audio, String mimeType) {
    }

    public record SpokenReplyMessage(String type, String audio, String mimeType, String transcript) {
    }

    public record TextReplyMessage(String type, String text, boolean fallback, String reason) {
    }

    public record ErrorMessage(String type, String code, String message) {
    }
}
