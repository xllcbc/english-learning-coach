package com.writelab.ai;

import com.writelab.config.ModelProperties;
import com.writelab.speech.AudioClip;
import com.writelab.speech.SpeechModel;
import com.writelab.speech.SpeechSynthesisException;
import com.writelab.speech.SpeechTranscriptionException;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.core.io.ByteArrayResource;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;

/**
 * Spring AI backed {@link SpeechModel}. Construction is manual, like the chat and embedding
 * factories, so Mock vs real mode stays a property of the application instead of Spring AI
 * autoconfiguration. The API key is passed straight to the provider options and never logged.
 */
public final class SpringAiSpeechModel implements SpeechModel {

    public static final String DEFAULT_TRANSCRIPTION_MODEL = "whisper-1";
    public static final String DEFAULT_SPEECH_MODEL = "tts-1";
    public static final String DEFAULT_VOICE = "alloy";
    public static final String DEFAULT_RESPONSE_MIME_TYPE = "audio/mpeg";

    private final TranscriptionModel transcriptionModel;
    private final TextToSpeechModel textToSpeechModel;
    private final String responseMimeType;

    SpringAiSpeechModel(
            TranscriptionModel transcriptionModel,
            TextToSpeechModel textToSpeechModel,
            String responseMimeType
    ) {
        this.transcriptionModel = transcriptionModel;
        this.textToSpeechModel = textToSpeechModel;
        this.responseMimeType = responseMimeType;
    }

    public static SpringAiSpeechModel create(ModelProperties properties) {
        OpenAiAudioTranscriptionOptions transcriptionOptions =
                OpenAiAudioTranscriptionOptions.builder()
                        .baseUrl(properties.baseUrl())
                        .apiKey(properties.apiKey())
                        .model(DEFAULT_TRANSCRIPTION_MODEL)
                        .timeout(properties.timeout())
                        .maxRetries(properties.maxRetries())
                        .build();
        OpenAiAudioSpeechOptions speechOptions = OpenAiAudioSpeechOptions.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .model(DEFAULT_SPEECH_MODEL)
                .voice(DEFAULT_VOICE)
                .responseFormat(OpenAiAudioSpeechOptions.AudioResponseFormat.MP3)
                .timeout(properties.timeout())
                .maxRetries(properties.maxRetries())
                .build();

        return new SpringAiSpeechModel(
                OpenAiAudioTranscriptionModel.builder().options(transcriptionOptions).build(),
                OpenAiAudioSpeechModel.builder().options(speechOptions).build(),
                DEFAULT_RESPONSE_MIME_TYPE);
    }

    @Override
    public String transcribe(AudioClip audio) throws SpeechTranscriptionException {
        try {
            return transcriptionModel.transcribe(audioResource(audio));
        } catch (RuntimeException failure) {
            throw new SpeechTranscriptionException(
                    reasonFor(failure), "Speech transcription failed", failure);
        }
    }

    @Override
    public AudioClip synthesize(String text) throws SpeechSynthesisException {
        try {
            return new AudioClip(textToSpeechModel.call(text), responseMimeType);
        } catch (RuntimeException failure) {
            throw new SpeechSynthesisException("Speech synthesis failed", failure);
        }
    }

    private static ByteArrayResource audioResource(AudioClip audio) {
        String filename = "speech." + extensionFor(audio.mimeType());
        return new ByteArrayResource(audio.bytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    /**
     * Whisper infers the container format from the uploaded filename, so the learner's mime type
     * has to be translated rather than dropped.
     */
    private static String extensionFor(String mimeType) {
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "audio/wav", "audio/x-wav", "audio/wave" -> "wav";
            case "audio/mp3", "audio/mpeg" -> "mp3";
            case "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a";
            case "audio/ogg" -> "ogg";
            case "audio/webm" -> "webm";
            default -> "webm";
        };
    }

    /**
     * The provider SDK wraps timeouts, so walk the cause chain instead of matching only the
     * outermost exception type.
     */
    private static SpeechTranscriptionException.Reason reasonFor(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException) {
                return SpeechTranscriptionException.Reason.TIMEOUT;
            }
        }
        return SpeechTranscriptionException.Reason.FAILED;
    }
}
