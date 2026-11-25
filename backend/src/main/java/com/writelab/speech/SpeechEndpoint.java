package com.writelab.speech;

import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpointConfig;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Base64;

/**
 * WebSocket adapter for Speaking Practice's speech channel. It owns transport concerns only:
 * decode the audio (or text fallback) payload, delegate the turn to {@link SpeechTurnService}, and
 * serialize either audio or text back to the learner.
 */
public class SpeechEndpoint extends Endpoint {

    static final String INVALID_MESSAGE_CODE = "INVALID_MESSAGE";
    static final String INVALID_JSON_TEXT = "消息不是有效的 JSON。";
    static final String UNSUPPORTED_TYPE_TEXT = "只支持 audio 或 text 消息。";
    static final String INVALID_AUDIO_TEXT = "audio 必须是 Base64。";
    static final String EMPTY_TEXT_TEXT = "text 不能为空。";

    private final SpeechTurnService speechTurnService;
    private final ObjectMapper objectMapper;

    public SpeechEndpoint(SpeechTurnService speechTurnService, ObjectMapper objectMapper) {
        this.speechTurnService = speechTurnService;
        this.objectMapper = objectMapper;
    }

    /**
     * Programmatic registration keeps the endpoint out of component scanning and lets the
     * configurator supply constructor dependencies. JSR-356 creates one endpoint per connection.
     */
    public static ServerEndpointConfig serverEndpointConfig(
            SpeechTurnService speechTurnService,
            ObjectMapper objectMapper,
            String path
    ) {
        return ServerEndpointConfig.Builder.create(SpeechEndpoint.class, path)
                .configurator(new Configurator(speechTurnService, objectMapper))
                .build();
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        session.addMessageHandler(String.class, message -> handle(session, message));
    }

    private void handle(Session session, String payload) {
        SpeechChannelMessages.SpeechInput input;
        try {
            input = objectMapper.readValue(payload, SpeechChannelMessages.SpeechInput.class);
        } catch (RuntimeException malformed) {
            sendError(session, INVALID_JSON_TEXT);
            return;
        }

        if (input == null) {
            sendError(session, INVALID_JSON_TEXT);
            return;
        }
        if (SpeechChannelMessages.TYPE_AUDIO.equals(input.type())) {
            handleAudio(session, input);
            return;
        }
        if (SpeechChannelMessages.TYPE_TEXT.equals(input.type())) {
            handleText(session, input);
            return;
        }
        sendError(session, UNSUPPORTED_TYPE_TEXT);
    }

    private void handleAudio(Session session, SpeechChannelMessages.SpeechInput input) {
        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(input.audio() == null ? "" : input.audio());
        } catch (IllegalArgumentException malformed) {
            sendError(session, INVALID_AUDIO_TEXT);
            return;
        }
        send(session, toMessage(
                speechTurnService.respond(new AudioClip(audioBytes, input.mimeType()))));
    }

    private void handleText(Session session, SpeechChannelMessages.SpeechInput input) {
        if (input.text() == null || input.text().isBlank()) {
            sendError(session, EMPTY_TEXT_TEXT);
            return;
        }
        send(session, toMessage(speechTurnService.respondToText(input.text())));
    }

    private SpeechChannelMessages.OutgoingMessage toMessage(
            SpeechTurnService.TurnResult result
    ) {
        return switch (result) {
            case SpeechTurnService.SpokenReply reply ->
                    new SpeechChannelMessages.SpokenReplyMessage(
                            SpeechChannelMessages.TYPE_AUDIO,
                            Base64.getEncoder().encodeToString(reply.audio().bytes()),
                            reply.audio().mimeType(),
                            reply.transcript());
            case SpeechTurnService.TextFallback fallback ->
                    new SpeechChannelMessages.TextReplyMessage(
                            SpeechChannelMessages.TYPE_TEXT,
                            fallback.text(),
                            true,
                            fallback.reason().wireValue());
        };
    }

    private void sendError(Session session, String message) {
        send(session, new SpeechChannelMessages.ErrorMessage(
                SpeechChannelMessages.TYPE_ERROR, INVALID_MESSAGE_CODE, message));
    }

    private void send(Session session, SpeechChannelMessages.OutgoingMessage message) {
        try {
            session.getBasicRemote().sendText(objectMapper.writeValueAsString(message));
        } catch (IOException | RuntimeException clientGone) {
            // The learner closed the channel mid-turn; there is no useful recovery here.
        }
    }

    private static final class Configurator extends ServerEndpointConfig.Configurator {

        private final SpeechTurnService speechTurnService;
        private final ObjectMapper objectMapper;

        private Configurator(SpeechTurnService speechTurnService, ObjectMapper objectMapper) {
            this.speechTurnService = speechTurnService;
            this.objectMapper = objectMapper;
        }

        @Override
        public <T> T getEndpointInstance(Class<T> endpointClass) {
            return endpointClass.cast(new SpeechEndpoint(speechTurnService, objectMapper));
        }
    }
}
