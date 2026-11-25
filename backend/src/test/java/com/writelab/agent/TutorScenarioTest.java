package com.writelab.agent;

import com.writelab.tools.LookupWordToolDefinition;
import com.writelab.tools.QueryLearningProgressToolDefinition;
import com.writelab.tools.RetrieveGroundingMaterialToolDefinition;
import com.writelab.tools.SearchUserDocumentsToolDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TutorScenarioTest {

    @Test
    void teacherConversationCanQueryLearningProgress() {
        assertThat(TutorScenario.TEACHER_CONVERSATION.allowedTools()).containsExactlyInAnyOrder(
                LookupWordToolDefinition.NAME,
                RetrieveGroundingMaterialToolDefinition.NAME,
                SearchUserDocumentsToolDefinition.NAME,
                QueryLearningProgressToolDefinition.NAME);
    }

    @Test
    void speakingPracticeCannotQueryLearningProgress() {
        assertThat(TutorScenario.SPEAKING_PRACTICE.allowedTools())
                .contains(LookupWordToolDefinition.NAME,
                        SearchUserDocumentsToolDefinition.NAME,
                        "save_vocabulary")
                .doesNotContain(QueryLearningProgressToolDefinition.NAME,
                        RetrieveGroundingMaterialToolDefinition.NAME);
    }
}
