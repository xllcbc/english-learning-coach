package com.writelab.speech;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Replay channel for Speaking Session. It is a pure read: opening a past session returns its
 * persisted turns, Agent Steps and Tool Invocations without calling STT, TTS or the model.
 */
@RestController
@RequestMapping("/api/speaking/sessions")
public class SpeakingSessionController {

    private final SpeakingPracticeService speakingPracticeService;

    public SpeakingSessionController(SpeakingPracticeService speakingPracticeService) {
        this.speakingPracticeService = speakingPracticeService;
    }

    @GetMapping
    public List<SpeakingSessionSummary> sessions() {
        return speakingPracticeService.sessions();
    }

    @GetMapping("/{sessionId}")
    public SpeakingSessionResponse session(@PathVariable UUID sessionId) {
        return speakingPracticeService.session(sessionId);
    }
}
