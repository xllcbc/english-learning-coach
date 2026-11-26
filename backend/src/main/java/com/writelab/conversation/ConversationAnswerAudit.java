package com.writelab.conversation;

import com.writelab.agent.AgentStep;
import com.writelab.agent.LookupWordSteps;
import com.writelab.agent.TutorCitableSource;
import com.writelab.agent.TutorToolSources;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * One Teacher Conversation answer after the citation rules have been applied. The service uses this
 * to decide whether the turn may be persisted; the gate evaluation uses the same object so a change
 * to the acceptance rule cannot silently drift away from what is evaluated.
 *
 * <p>A no-marker answer is allowed when there is nothing to cite: that is how the teacher refuses
 * to answer without a grounded source. A marker with no matching source is invented provenance. An
 * answer with citable sources that cites none is an unsupported assertion.</p>
 */
record ConversationAnswerAudit(
        boolean blank,
        boolean unsupportedReferences,
        boolean hasCitableSource,
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
                cited);
    }

    boolean accepted(boolean completed) {
        return completed && !blank && !unsupportedReferences
                && !(hasCitableSource && citations.isEmpty());
    }
}
