package com.writelab.speech;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket adapter for Speaking Practice's speech channel. It owns transport concerns only:
 * decode the audio (or text fallback) payload, delegate the turn to
 * {@link SpeakingPracticeService}, and serialize either audio or text back to the learner.
 */
public class SpeechEndpoint extends Endpoint {

    private static final Logger log = LoggerFactory.getLogger(SpeechEndpoint.class);

    static final String INVALID_MESSAGE_CODE = "INVALID_MESSAGE";
    static final String SESSION_NOT_FOUND_CODE = "SPEAKING_SESSION_NOT_FOUND";
    static final String TURN_FAILED_CODE = "SPEAKING_TURN_FAILED";
    static final String INVALID_JSON_TEXT = "消息不是有效的 JSON。";
    static final String UNSUPPORTED_TYPE_TEXT = "只支持 audio 或 text 消息。";
    static final String INVALID_AUDIO_TEXT = "audio 必须是 Base64。";
    static final String EMPTY_TEXT_TEXT = "text 不能为空。";
    static final String INVALID_SESSION_TEXT = "sessionId 必须是有效的 UUID。";
    static final String SESSION_NOT_FOUND_TEXT = "找不到对应的 Speaking Session，请开始新的练习。";
    static final String TURN_FAILED_TEXT = "口语回复生成失败，请重试。";

    private final SpeakingPracticeService speakingPracticeService;
    private final ObjectMapper objectMapper;
    private final Object sendLock = new Object();
    private final AtomicLong turnGeneration = new AtomicLong();
    private final ExecutorService turnExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "speech-turn");
        thread.setDaemon(true);
        return thread;
    });

    public SpeechEndpoint(
            SpeakingPracticeService speakingPracticeService,
            ObjectMapper objectMapper
    ) {
        this.speakingPracticeService = speakingPracticeService;
        this.objectMapper = objectMapper;
    }

    /**
     * Programmatic registration keeps the endpoint out of component scanning and lets the
     * configurator supply constructor dependencies. JSR-356 creates one endpoint per connection.
     */
    public static ServerEndpointConfig serverEndpointConfig(
            SpeakingPracticeService speakingPracticeService,
            ObjectMapper objectMapper,
            String path
    ) {
        return ServerEndpointConfig.Builder.create(SpeechEndpoint.class, path)
                .configurator(new Configurator(speakingPracticeService, objectMapper))
                .build();
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        session.addMessageHandler(String.class, message -> handle(session, message));
    }

    @Override
    public void onClose(Session session, CloseReason closeReason) {
        turnExecutor.shutdownNow();
    }

    private void handle(Session session, String payload) {
        SpeechChannelMessages.SpeechInput input;
        try {
            input = objectMapper.readValue(payload, SpeechChannelMessages.SpeechInput.class);
        } catch (RuntimeException malformed) {
            sendError(session, INVALID_MESSAGE_CODE, INVALID_JSON_TEXT);
            return;
        }

        if (input == null) {
            sendError(session, INVALID_MESSAGE_CODE, INVALID_JSON_TEXT);
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
        if (SpeechChannelMessages.TYPE_BARGE_IN.equals(input.type())) {
            handleBargeIn(session, input);
            return;
        }
        sendError(session, INVALID_MESSAGE_CODE, UNSUPPORTED_TYPE_TEXT);
    }

    /**
     * Barge-in is a channel concern (ADR-0009, ADR-0011): the learner started speaking, so any
     * reply this connection is still producing is abandoned instead of being played over the
     * learner's new utterance. Acknowledgements make the cancellation observable to the client.
     */
    private void handleBargeIn(Session session, SpeechChannelMessages.SpeechInput input) {
        UUID sessionId;
        try {
            sessionId = parseSessionId(input.sessionId());
        } catch (IllegalArgumentException invalid) {
            sendError(session, INVALID_MESSAGE_CODE, INVALID_SESSION_TEXT);
            return;
        }
        turnGeneration.incrementAndGet();
        send(session, new SpeechChannelMessages.InterruptedMessage(
                SpeechChannelMessages.TYPE_INTERRUPTED,
                sessionId == null ? null : sessionId.toString(),
                SpeechChannelMessages.BARGE_IN_REASON));
    }

    private void handleAudio(Session session, SpeechChannelMessages.SpeechInput input) {
        UUID sessionId;
        try {
            sessionId = parseSessionId(input.sessionId());
        } catch (IllegalArgumentException invalid) {
            sendError(session, INVALID_MESSAGE_CODE, INVALID_SESSION_TEXT);
            return;
        }

        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(input.audio() == null ? "" : input.audio());
        } catch (IllegalArgumentException malformed) {
            sendError(session, INVALID_MESSAGE_CODE, INVALID_AUDIO_TEXT);
            return;
        }
        respond(session, () -> speakingPracticeService.respond(
                new AudioClip(audioBytes, input.mimeType()), sessionId));
    }

    private void handleText(Session session, SpeechChannelMessages.SpeechInput input) {
        if (input.text() == null || input.text().isBlank()) {
            sendError(session, INVALID_MESSAGE_CODE, EMPTY_TEXT_TEXT);
            return;
        }
        UUID sessionId;
        try {
            sessionId = parseSessionId(input.sessionId());
        } catch (IllegalArgumentException invalid) {
            sendError(session, INVALID_MESSAGE_CODE, INVALID_SESSION_TEXT);
            return;
        }
        respond(session, () -> speakingPracticeService.respondToText(input.text(), sessionId));
    }

    /**
     * Turns run off the container's message thread so a barge-in can be received while a reply is
     * still being generated. Each new input (or barge-in) supersedes the previous generation; a
     * superseded turn may persist its audit record but must never deliver its stale reply.
     */
    private void respond(Session session, TurnSupplier turnSupplier) {
        long generation = turnGeneration.incrementAndGet();
        turnExecutor.execute(() -> {
            SpeakingPracticeService.SpeakingTurnResult result;
            try {
                result = turnSupplier.get();
            } catch (SpeakingSessionNotFoundException missing) {
                if (isCurrent(generation)) {
                    sendError(session, SESSION_NOT_FOUND_CODE, SESSION_NOT_FOUND_TEXT);
                }
                return;
            } catch (RuntimeException failure) {
                log.warn("口语回合生成失败: {}", failure.getMessage());
                if (isCurrent(generation)) {
                    sendError(session, TURN_FAILED_CODE, TURN_FAILED_TEXT);
                }
                return;
            }
            if (isCurrent(generation)) {
                send(session, toMessage(result));
            }
        });
    }

    private boolean isCurrent(long generation) {
        return turnGeneration.get() == generation;
    }

    private SpeechChannelMessages.OutgoingMessage toMessage(
            SpeakingPracticeService.SpeakingTurnResult result
    ) {
        return switch (result) {
            case SpeakingPracticeService.SpokenReply reply ->
                    new SpeechChannelMessages.SpokenReplyMessage(
                            SpeechChannelMessages.TYPE_AUDIO,
                            reply.turn().sessionId().toString(),
                            Base64.getEncoder().encodeToString(reply.audio().bytes()),
                            reply.audio().mimeType(),
                            reply.turn().transcript(),
                            reply.turn().reply(),
                            reply.turn().inputMode().name(),
                            reply.turn().mockMode(),
                            reply.turn().latencyMs());
            case SpeakingPracticeService.TextReply fallback ->
                    new SpeechChannelMessages.TextReplyMessage(
                            SpeechChannelMessages.TYPE_TEXT,
                            fallback.turn().sessionId().toString(),
                            fallback.text(),
                            true,
                            fallback.reason().wireValue(),
                            fallback.turn().transcript(),
                            fallback.turn().inputMode().name(),
                            fallback.turn().mockMode(),
                            fallback.turn().latencyMs());
        };
    }

    private static UUID parseSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return UUID.fromString(sessionId);
    }

    private void sendError(Session session, String code, String message) {
        send(session, new SpeechChannelMessages.ErrorMessage(
                SpeechChannelMessages.TYPE_ERROR, code, message));
    }

    private void send(Session session, SpeechChannelMessages.OutgoingMessage message) {
        try {
            String payload = objectMapper.writeValueAsString(message);
            synchronized (sendLock) {
                session.getBasicRemote().sendText(payload);
            }
        } catch (IOException | RuntimeException clientGone) {
            // The learner closed the channel mid-turn; there is no useful recovery here.
        }
    }

    @FunctionalInterface
    private interface TurnSupplier {
        SpeakingPracticeService.SpeakingTurnResult get();
    }

    private static final class Configurator extends ServerEndpointConfig.Configurator {

        private final SpeakingPracticeService speakingPracticeService;
        private final ObjectMapper objectMapper;

        private Configurator(
                SpeakingPracticeService speakingPracticeService,
                ObjectMapper objectMapper
        ) {
            this.speakingPracticeService = speakingPracticeService;
            this.objectMapper = objectMapper;
        }

        @Override
        public <T> T getEndpointInstance(Class<T> endpointClass) {
            return endpointClass.cast(
                    new SpeechEndpoint(speakingPracticeService, objectMapper));
        }
    }
}
