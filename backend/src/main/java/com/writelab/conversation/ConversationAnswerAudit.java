package com.writelab.conversation;

import com.writelab.agent.AgentStep;
import com.writelab.agent.LookupWordSteps;
import com.writelab.agent.TutorCitableSource;
import com.writelab.agent.TutorRefusal;
import com.writelab.agent.TutorToolSources;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * One Teacher Conversation answer after the citation rules have been applied. The service uses this
 * to decide whether the turn may be persisted; the gate evaluation uses the same object so a change
 * to the acceptance rule cannot silently drift away from what is evaluated.
 *
 * <p>A no-marker answer is allowed only when it is the explicit {@link
 * com.writelab.agent.TutorRefusal#NO_GROUNDED_SOURCE} refusal: that is how the teacher says it
 * lacks a grounded source. Any other no-source answer is an ungrounded assertion. A marker with no
 * matching source is invented provenance. An answer with citable sources that cites none is an
 * unsupported assertion.</p>
 */
record ConversationAnswerAudit(
        boolean blank,
        boolean unsupportedReferences,
        boolean hasCitableSource,
        boolean refusal,
        List<ConversationCitation> citations
) {

    static ConversationAnswerAudit inspect(
            String answer,
            List<TutorCitableSource> briefingSources,
            List<AgentStep> steps,
            ObjectMapper objectMapper
    ) {
        List<ConversationCitation> available = ConversationCitations.available(
                briefingSources, steps, objectMapper);
        List<ConversationCitation> cited = ConversationCitations.cited(answer, available);
        boolean unsupported = ConversationCitations.hasUnsupportedReferences(answer, cited);
        boolean hasCitableSource = !briefingSources.isEmpty()
                || !LookupWordSteps.successful(steps).isEmpty()
                || !TutorToolSources.successful(steps, objectMapper).isEmpty();
        return new ConversationAnswerAudit(
                answer == null || answer.isBlank(),
                unsupported,
                hasCitableSource,
                TutorRefusal.isNoGroundedSourceRefusal(answer),
                cited);
    }

    boolean accepted(boolean completed) {
        return rejectionReason(completed) == RejectionReason.NONE;
    }

    RejectionReason rejectionReason(boolean completed) {
        if (blank) {
            return RejectionReason.BLANK_ANSWER;
        }
        if (unsupportedReferences) {
            return RejectionReason.UNSUPPORTED_REFERENCE;
        }
        if (!hasCitableSource && !refusal) {
            return RejectionReason.UNGROUNDED_ANSWER;
        }
        if (completed && hasCitableSource && citations.isEmpty()) {
            return RejectionReason.NO_CITATION;
        }
        return RejectionReason.NONE;
    }

    enum RejectionReason {
        NONE,
        BLANK_ANSWER,
        UNSUPPORTED_REFERENCE,
        UNGROUNDED_ANSWER,
        NO_CITATION
    }
}
