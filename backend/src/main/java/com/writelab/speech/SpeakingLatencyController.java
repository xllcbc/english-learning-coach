package com.writelab.speech;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the observed speaking latency so the budget from ADR-0011 is observable instead of
 * living only in logs. The sample is every spoken turn whose reply was synthesized as audio:
 * turns that never start playing (typed input, transcription or synthesis failures) are excluded.
 */
@RestController
@RequestMapping("/api/speaking/latency")
public class SpeakingLatencyController {

    private final SpeakingPracticeService speakingPracticeService;

    public SpeakingLatencyController(SpeakingPracticeService speakingPracticeService) {
        this.speakingPracticeService = speakingPracticeService;
    }

    @GetMapping
    public SpeakingLatencyStats latency() {
        return speakingPracticeService.latency();
    }
}
