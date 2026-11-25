package com.writelab.speech;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.apache.tomcat.websocket.server.WsSci;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
 * The external contract of the voice channel: a real WebSocket client sends an audio payload and
 * the server answers with audio, or degrades to a text message when speech recognition fails or
 * times out (ADR-0011). No microphone, container or real provider is involved.
 */
class SpeechChannelContractTest {

    private static final byte[] LEARNER_AUDIO =
            "learner audio".getBytes(StandardCharsets.UTF_8);
    private static final String AUDIO_MIME_TYPE = "audio/webm";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AnnotationConfigServletWebServerApplicationContext context;
    private WebSocket webSocket;
    private CollectingListener listener;

    @AfterEach
    void stop() throws Exception {
        if (webSocket != null) {
            webSocket.abort();
        }
        if (context != null) {
            context.close();
        }
    }

    @Test
    void receivesAudioAndReturnsSynthesizedAudio() throws Exception {
        start(new MockSpeechModel());

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
        start(new FailingTranscriptionSpeechModel(SpeechTranscriptionException.Reason.FAILED));

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
        start(new FailingTranscriptionSpeechModel(SpeechTranscriptionException.Reason.TIMEOUT));

        sendAudio();

        SpeechChannelMessages.TextReplyMessage reply = objectMapper.readValue(
                listener.awaitMessage(), SpeechChannelMessages.TextReplyMessage.class);
        assertThat(reply.type()).isEqualTo(SpeechChannelMessages.TYPE_TEXT);
        assertThat(reply.fallback()).isTrue();
        assertThat(reply.reason()).isEqualTo("timeout");
        assertThat(reply.text()).contains("文字输入");
    }

    private void start(SpeechModel speechModel) throws Exception {
        TomcatServletWebServerFactory webServerFactory = new TomcatServletWebServerFactory();
        webServerFactory.setPort(0);
        // In the full application Boot's WebSocket auto-configuration adds this initializer.
        webServerFactory.addContextCustomizers(
                context -> context.addServletContainerInitializer(new WsSci(), null));

        context = new AnnotationConfigServletWebServerApplicationContext();
        context.registerBean(TomcatServletWebServerFactory.class, () -> webServerFactory);
        context.registerBean(ObjectMapper.class, () -> objectMapper);
        context.registerBean(SpeechModel.class, () -> speechModel);
        context.registerBean("catchAllServlet", ServletRegistrationBean.class, () -> {
            // A servlet mapping is required for Tomcat to build the filter chain; the WebSocket
            // upgrade filter runs before this catch-all and never reaches it on a valid handshake.
            var registration = new ServletRegistrationBean<>(new HttpServlet() {
                @Override
                protected void service(HttpServletRequest request, HttpServletResponse response)
                        throws IOException {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                }
            }, "/*");
            return registration;
        });
        context.register(SpeechChannelConfiguration.class);
        context.refresh();

        int port = context.getWebServer().getPort();
        listener = new CollectingListener();
        webSocket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(
                        URI.create("ws://127.0.0.1:" + port + SpeechChannelConfiguration.PATH),
                        listener)
                .get(5, TimeUnit.SECONDS);
    }

    private void sendAudio() throws Exception {
        String payload = objectMapper.writeValueAsString(new SpeechChannelMessages.SpeechInput(
                SpeechChannelMessages.TYPE_AUDIO,
                Base64.getEncoder().encodeToString(LEARNER_AUDIO),
                AUDIO_MIME_TYPE));
        webSocket.sendText(payload, true).get(5, TimeUnit.SECONDS);
    }

    private static final class FailingTranscriptionSpeechModel implements SpeechModel {

        private final SpeechTranscriptionException failure;

        private FailingTranscriptionSpeechModel(SpeechTranscriptionException.Reason reason) {
            this.failure = new SpeechTranscriptionException(reason, "scripted failure");
        }

        @Override
        public String transcribe(AudioClip audio) throws SpeechTranscriptionException {
            throw failure;
        }

        @Override
        public AudioClip synthesize(String text) {
            throw new AssertionError("synthesis must not run after a transcription failure");
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
