package com.writelab.ai;

import com.writelab.speech.AudioClip;
import com.writelab.speech.SpeechFailureReason;
import com.writelab.speech.SpeechSynthesisException;
import com.writelab.speech.SpeechTranscriptionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.core.io.Resource;

import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the provider-adapter behaviour that the deterministic fake cannot cover: upload format,
 * output mime type and the STT timeout mapping used by the text degradation path.
 */
class SpringAiSpeechModelTest {

    private final TranscriptionModel transcriptionModel = mock(TranscriptionModel.class);
    private final TextToSpeechModel textToSpeechModel = mock(TextToSpeechModel.class);
    private final SpringAiSpeechModel model =
            new SpringAiSpeechModel(transcriptionModel, textToSpeechModel, "audio/mpeg");

    @Test
    void transcribesAndPassesTheContainerFormatThroughTheFilename() {
        when(transcriptionModel.transcribe(any(Resource.class))).thenReturn("hello");

        String transcript = model.transcribe(
                new AudioClip("bytes".getBytes(StandardCharsets.UTF_8), "audio/webm"));

        assertThat(transcript).isEqualTo("hello");
        ArgumentCaptor<Resource> resource = ArgumentCaptor.forClass(Resource.class);
        verify(transcriptionModel).transcribe(resource.capture());
        assertThat(resource.getValue().getFilename()).isEqualTo("speech.webm");
    }

    @Test
    void mapsAWrappedHttpTimeoutToTheTimeoutReason() {
        when(transcriptionModel.transcribe(any(Resource.class)))
                .thenThrow(new RuntimeException(new HttpTimeoutException("slow")));

        assertThatThrownBy(() -> model.transcribe(
                new AudioClip("bytes".getBytes(StandardCharsets.UTF_8), "audio/webm")))
                .isInstanceOfSatisfying(SpeechTranscriptionException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(SpeechFailureReason.TIMEOUT));
    }

    @Test
    void mapsOtherProviderFailuresToTheFailedReason() {
        when(transcriptionModel.transcribe(any(Resource.class)))
                .thenThrow(new IllegalStateException("provider error"));

        assertThatThrownBy(() -> model.transcribe(
                new AudioClip("bytes".getBytes(StandardCharsets.UTF_8), "audio/webm")))
                .isInstanceOfSatisfying(SpeechTranscriptionException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(SpeechFailureReason.FAILED));
    }

    @Test
    void synthesizesWithTheConfiguredOutputMimeType() {
        byte[] audio = "audio".getBytes(StandardCharsets.UTF_8);
        when(textToSpeechModel.call("hello")).thenReturn(audio);

        assertThat(model.synthesize("hello")).isEqualTo(new AudioClip(audio, "audio/mpeg"));
    }

    @Test
    void wrapsSynthesisFailures() {
        when(textToSpeechModel.call("hello")).thenThrow(new IllegalStateException("provider error"));

        assertThatThrownBy(() -> model.synthesize("hello"))
                .isInstanceOf(SpeechSynthesisException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
