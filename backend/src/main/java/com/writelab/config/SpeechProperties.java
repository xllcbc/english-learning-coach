package com.writelab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Speech provider settings. The timeout is separate from the chat-model timeout because a spoken
 * turn has a much tighter latency budget (ADR-0011); a 120s chat timeout would let the learner wait
 * far past the visible "thinking" state.
 */
@ConfigurationProperties(prefix = "writelab.speech")
public record SpeechProperties(Duration timeout) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    public SpeechProperties {
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }
}
