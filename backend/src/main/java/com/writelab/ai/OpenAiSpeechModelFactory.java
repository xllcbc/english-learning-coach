package com.writelab.ai;

import com.writelab.config.ModelProperties;
import com.writelab.config.SpeechProperties;
import com.writelab.speech.SpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;

/**
 * Builds the real {@link SpeechModel} from {@link ModelProperties} and {@link SpeechProperties},
 * mirroring the existing chat and embedding factories. A blank API key never reaches this class:
 * {@code ModelConfiguration} selects the Mock adapter first.
 */
public final class OpenAiSpeechModelFactory {

    public static final String DEFAULT_TRANSCRIPTION_MODEL = "whisper-1";
    public static final String DEFAULT_SPEECH_MODEL = "tts-1";
    public static final String DEFAULT_VOICE = "alloy";
    public static final String RESPONSE_MIME_TYPE = "audio/mpeg";

    private OpenAiSpeechModelFactory() {
    }

    public static SpeechModel create(ModelProperties properties, SpeechProperties speechProperties) {
        OpenAiAudioTranscriptionOptions transcriptionOptions =
                OpenAiAudioTranscriptionOptions.builder()
                        .baseUrl(properties.baseUrl())
                        .apiKey(properties.apiKey())
                        .model(DEFAULT_TRANSCRIPTION_MODEL)
                        .timeout(speechProperties.timeout())
                        .maxRetries(properties.maxRetries())
                        .build();
        OpenAiAudioSpeechOptions speechOptions = OpenAiAudioSpeechOptions.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .model(DEFAULT_SPEECH_MODEL)
                .voice(DEFAULT_VOICE)
                .responseFormat(OpenAiAudioSpeechOptions.AudioResponseFormat.MP3)
                .timeout(speechProperties.timeout())
                .maxRetries(properties.maxRetries())
                .build();

        return new SpringAiSpeechModel(
                OpenAiAudioTranscriptionModel.builder().options(transcriptionOptions).build(),
                OpenAiAudioSpeechModel.builder().options(speechOptions).build(),
                RESPONSE_MIME_TYPE);
    }
}
