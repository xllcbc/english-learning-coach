package com.writelab.ai;

import com.writelab.speech.AudioClip;
import com.writelab.speech.SpeechFailureReason;
import com.writelab.speech.SpeechModel;
import com.writelab.speech.SpeechSynthesisException;
import com.writelab.speech.SpeechTranscriptionException;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.core.io.ByteArrayResource;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Spring AI backed {@link SpeechModel}. Construction lives in {@link OpenAiSpeechModelFactory}, so
 * this class only adapts provider responses and failures to the speech seam.
 */
public final class SpringAiSpeechModel implements SpeechModel {

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

    @Override
    public String transcribe(AudioClip audio) {
        try {
            return transcriptionModel.transcribe(audioResource(audio));
        } catch (RuntimeException failure) {
            throw new SpeechTranscriptionException(
                    reasonFor(failure), "Speech transcription failed", failure);
        }
    }

    @Override
    public AudioClip synthesize(String text) {
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
            default -> "webm";
        };
    }

    /**
     * The provider SDK wraps timeouts, so walk the cause chain instead of matching only the
     * outermost exception type.
     */
    private static SpeechFailureReason reasonFor(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException) {
                return SpeechFailureReason.TIMEOUT;
            }
        }
        return SpeechFailureReason.FAILED;
    }
}
