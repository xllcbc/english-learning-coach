package com.writelab.speech;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The external contract of the speech channel: a real WebSocket client sends an audio payload and
 * the server answers with audio, or degrades to a text message when speech recognition fails or
 * times out (ADR-0011). A real Mock-mode Spring Boot server is used, so Boot's WebSocket
 * auto-configuration, the Mock {@link SpeechModel} bean and the endpoint registration are all
 * exercised. No microphone, container or real provider is involved.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = SpeechChannelContractTest.TestApplication.class,
        properties = {
                "writelab.model.api-key=",
                "spring.flyway.enabled=false",
                "spring.datasource.url=",
                "spring.datasource.username=",
                "spring.datasource.password="
        }
)
@Import(SpeechChannelContractTest.ScriptedSpeechModelConfiguration.class)
class SpeechChannelContractTest {

    private static final byte[] LEARNER_AUDIO =
            "learner audio".getBytes(StandardCharsets.UTF_8);
    private static final String AUDIO_MIME_TYPE = "audio/webm";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScriptedSpeechModel speechModel;

    private WebSocket webSocket;
    private CollectingListener listener;

    @BeforeEach
    void openChannel() throws Exception {
        speechModel.reset();
        listener = new CollectingListener();
        webSocket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(
                        URI.create("ws://127.0.0.1:" + port + SpeechChannelConfiguration.PATH),
                        listener)
                .get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void closeChannel() {
        if (webSocket != null) {
            webSocket.abort();
        }
    }

    @Test
    void receivesAudioAndReturnsSynthesizedAudio() throws Exception {
        sendAudio();

        SpeechChannelMessages.SpokenReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.SpokenReplyMessage.class);
        assertThat(reply.type()).isEqualTo(SpeechChannelMessages.TYPE_AUDIO);
        assertThat(reply.transcript()).isEqualTo(MockSpeechModel.FIXED_TRANSCRIPT);
        assertThat(reply.mimeType()).isEqualTo(MockSpeechModel.FIXED_AUDIO_MIME_TYPE);
        assertThat(Base64.getDecoder().decode(reply.audio())).isEqualTo(MockSpeechModel.fixedAudio());
    }

    @Test
    void degradesToTextWhenTranscriptionFails() throws Exception {
        speechModel.failTranscriptionWith(SpeechFailureReason.FAILED);

        sendAudio();

        SpeechChannelMessages.TextReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.TextReplyMessage.class);
        assertThat(reply.type()).isEqualTo(SpeechChannelMessages.TYPE_TEXT);
        assertThat(reply.fallback()).isTrue();
        assertThat(reply.reason()).isEqualTo("failed");
        assertThat(reply.text()).contains("文字输入");
    }

    @Test
    void degradesToTextWhenTranscriptionTimesOut() throws Exception {
        speechModel.failTranscriptionWith(SpeechFailureReason.TIMEOUT);

        sendAudio();

        SpeechChannelMessages.TextReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.TextReplyMessage.class);
        assertThat(reply.type()).isEqualTo(SpeechChannelMessages.TYPE_TEXT);
        assertThat(reply.fallback()).isTrue();
        assertThat(reply.reason()).isEqualTo("timeout");
        assertThat(reply.text()).contains("文字输入");
    }

    @Test
    void acceptsTypedTextAfterATranscriptionFallback() throws Exception {
        speechModel.failTranscriptionWith(SpeechFailureReason.TIMEOUT);
        sendAudio();
        objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.TextReplyMessage.class);

        String payload = objectMapper.writeValueAsString(new SpeechChannelMessages.SpeechInput(
                SpeechChannelMessages.TYPE_TEXT, null, null, "I will type this instead."));
        webSocket.sendText(payload, true).get(5, TimeUnit.SECONDS);

        SpeechChannelMessages.SpokenReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.SpokenReplyMessage.class);
        assertThat(reply.type()).isEqualTo(SpeechChannelMessages.TYPE_AUDIO);
        assertThat(reply.transcript()).isEqualTo("I will type this instead.");
        assertThat(Base64.getDecoder().decode(reply.audio())).isEqualTo(MockSpeechModel.fixedAudio());
    }

    private void sendAudio() throws Exception {
        String payload = objectMapper.writeValueAsString(new SpeechChannelMessages.SpeechInput(
                SpeechChannelMessages.TYPE_AUDIO,
                Base64.getEncoder().encodeToString(LEARNER_AUDIO),
                AUDIO_MIME_TYPE,
                null));
        webSocket.sendText(payload, true).get(5, TimeUnit.SECONDS);
    }

    @SpringBootApplication(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            JdbcClientAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    static class TestApplication {
    }

    @TestConfiguration
    static class ScriptedSpeechModelConfiguration {

        @Bean
        @Primary
        ScriptedSpeechModel scriptedSpeechModel() {
            return new ScriptedSpeechModel();
        }
    }

    static final class ScriptedSpeechModel implements SpeechModel {

        private final MockSpeechModel delegate = new MockSpeechModel();
        private SpeechTranscriptionException transcriptionFailure;

        @Override
        public String transcribe(AudioClip audio) {
            if (transcriptionFailure != null) {
                throw transcriptionFailure;
            }
            return delegate.transcribe(audio);
        }

        @Override
        public AudioClip synthesize(String text) {
            return delegate.synthesize(text);
        }

        void failTranscriptionWith(SpeechFailureReason reason) {
            transcriptionFailure = new SpeechTranscriptionException(reason, "scripted failure");
        }

        void reset() {
            transcriptionFailure = null;
        }
    }

    private static final class CollectingListener implements WebSocket.Listener {

        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                messages.add(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        private String awaitMessage() throws InterruptedException {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).as("WebSocket reply").isNotNull();
            return message;
        }
    }
}
