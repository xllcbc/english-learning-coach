package com.writelab.speech;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the observed "finished speaking to playback" latency so the budget from ADR-0011 is
 * observable instead of living only in logs. The sample is every persisted turn that produced a
 * playable reply.
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
