package com.writelab.speech;

import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpointConfig;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Base64;

/**
 * WebSocket adapter for the voice channel. It owns transport concerns only: decode the audio
 * payload, delegate the spoken turn to {@link SpeechTurnService}, and serialize either audio or a
 * text fallback back to the learner.
 */
public class SpeechEndpoint extends Endpoint {

    static final String INVALID_MESSAGE_CODE = "INVALID_MESSAGE";

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
            send(session, new SpeechChannelMessages.ErrorMessage(
                    SpeechChannelMessages.TYPE_ERROR, INVALID_MESSAGE_CODE, "消息不是有效的 JSON。"));
            return;
        }

        if (input == null || !SpeechChannelMessages.TYPE_AUDIO.equals(input.type())) {
            send(session, new SpeechChannelMessages.ErrorMessage(
                    SpeechChannelMessages.TYPE_ERROR, INVALID_MESSAGE_CODE, "只支持 audio 消息。"));
            return;
        }

        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(input.audio() == null ? "" : input.audio());
        } catch (IllegalArgumentException malformed) {
            send(session, new SpeechChannelMessages.ErrorMessage(
                    SpeechChannelMessages.TYPE_ERROR, INVALID_MESSAGE_CODE, "audio 必须是 Base64。"));
            return;
        }

        SpeechTurnService.TurnResult result =
                speechTurnService.respond(new AudioClip(audioBytes, input.mimeType()));
        send(session, toMessage(result));
    }

    private Object toMessage(SpeechTurnService.TurnResult result) {
        if (result instanceof SpeechTurnService.SpokenReply reply) {
            return new SpeechChannelMessages.SpokenReplyMessage(
                    SpeechChannelMessages.TYPE_AUDIO,
                    Base64.getEncoder().encodeToString(reply.audio().bytes()),
                    reply.audio().mimeType(),
                    reply.transcript());
        }
        SpeechTurnService.TextFallback fallback = (SpeechTurnService.TextFallback) result;
        return new SpeechChannelMessages.TextReplyMessage(
                SpeechChannelMessages.TYPE_TEXT,
                fallback.text(),
                true,
                fallback.reason().wireValue());
    }

    private void send(Session session, Object message) {
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
