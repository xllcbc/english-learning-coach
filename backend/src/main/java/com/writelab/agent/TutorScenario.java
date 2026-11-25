package com.writelab.agent;

import com.writelab.tools.LookupWordToolDefinition;
import com.writelab.tools.QueryLearningProgressToolDefinition;
import com.writelab.tools.RetrieveGroundingMaterialToolDefinition;
import com.writelab.tools.SearchUserDocumentsToolDefinition;

import java.util.Set;

/**
 * The scenario an agent turn belongs to. The bounded core stays channel-agnostic; the scenario
 * owns the allowlist that narrows the shared tool registry, so a tool added to the registry cannot
 * leak into a scenario without an explicit entry here.
 */
public enum TutorScenario {
    TEACHER_CONVERSATION(
            LookupWordToolDefinition.NAME,
            RetrieveGroundingMaterialToolDefinition.NAME,
            SearchUserDocumentsToolDefinition.NAME,
            QueryLearningProgressToolDefinition.NAME),
    SPEAKING_PRACTICE(
            LookupWordToolDefinition.NAME,
            // The parent spec gives Speaking Practice these tools too. Neither is wired into this
            // scenario yet, so keep them out of the model context until that slice lands.
            SearchUserDocumentsToolDefinition.NAME,
            "save_vocabulary");

    private final Set<String> allowedTools;

    TutorScenario(String... allowedTools) {
        this.allowedTools = Set.of(allowedTools);
    }

    public Set<String> allowedTools() {
        return allowedTools;
    }
}
