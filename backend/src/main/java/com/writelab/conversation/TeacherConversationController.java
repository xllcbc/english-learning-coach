package com.writelab.conversation;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Teacher Conversation channel adapter. Opening the conversation is a plain read so re-rendering a
 * Practice Attempt replays stored turns; asking a question streams the new turn over SSE.
 */
@RestController
@RequestMapping("/api/practices/{practiceId}/conversation")
public class TeacherConversationController {

    private final TeacherConversationService teacherConversationService;

    public TeacherConversationController(TeacherConversationService teacherConversationService) {
        this.teacherConversationService = teacherConversationService;
    }

    @GetMapping
    public TeacherConversationResponse conversation(@PathVariable UUID practiceId) {
        return teacherConversationService.conversation(practiceId);
    }

    @PostMapping(
            path = "/turns",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(
            @PathVariable UUID practiceId,
            @Valid @RequestBody TeacherConversationRequest request
    ) {
        return teacherConversationService.ask(practiceId, request.message());
    }
}
