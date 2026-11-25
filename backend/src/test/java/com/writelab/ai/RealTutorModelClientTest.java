package com.writelab.ai;

import com.writelab.agent.TutorModelDecision;
import com.writelab.agent.TutorScenario;
import com.writelab.feedback.IssueCategory;
import com.writelab.feedback.IssueSeverity;
import com.writelab.feedback.LearningIssue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model's JSON contract is the production extraction path for Speaking Practice. These cases
 * pin the answer-plus-issues shape and the decision to skip malformed entries rather than fail the
 * learner's turn.
 */
class RealTutorModelClientTest {

    private final RealTutorModelClient client = new RealTutorModelClient(null, new ObjectMapper());

    @Test
    void readsLearningIssuesFromASpeakingAnswer() {
        TutorModelDecision decision = client.parse("""
                {"type":"answer","content":"注意时态。","issues":[
                  {"originalSentence":"I go to the park yesterday.",
                   "suggestion":"I went to the park yesterday.",
                   "category":"grammar","severity":"minor","explanation":"过去时间要用过去式。"}
                ]}
                """, TutorScenario.SPEAKING_PRACTICE);

        TutorModelDecision.Answer answer = (TutorModelDecision.Answer) decision;
        assertThat(answer.content()).isEqualTo("注意时态。");
        assertThat(answer.issues()).hasSize(1);

        LearningIssue issue = answer.issues().getFirst();
        assertThat(issue.originalSentence()).isEqualTo("I go to the park yesterday.");
        assertThat(issue.suggestion()).isEqualTo("I went to the park yesterday.");
        assertThat(issue.category()).isEqualTo(IssueCategory.GRAMMAR);
        assertThat(issue.severity()).isEqualTo(IssueSeverity.MINOR);
        assertThat(issue.explanation()).contains("过去式");
    }

    @Test
    void skipsMalformedIssuesWithoutLosingTheAnswer() {
        TutorModelDecision decision = client.parse("""
                {"type":"answer","content":"注意时态。","issues":[
                  {"originalSentence":"I go to the park yesterday.","suggestion":"I went to the park yesterday.",
                   "category":"TENSE","severity":"minor","explanation":"类别不存在。"},
                  {"originalSentence":"","suggestion":"I went to the park yesterday.",
                   "category":"GRAMMAR","severity":"minor","explanation":"没有原句。"},
                  {"originalSentence":"I go to the park yesterday.","suggestion":"I went to the park yesterday.",
                   "category":"GRAMMAR","severity":"minor","explanation":"有效的问题。"}
                ]}
                """, TutorScenario.SPEAKING_PRACTICE);

        TutorModelDecision.Answer answer = (TutorModelDecision.Answer) decision;
        assertThat(answer.content()).isEqualTo("注意时态。");
        assertThat(answer.issues()).singleElement()
                .extracting(LearningIssue::explanation)
                .isEqualTo("有效的问题。");
    }

    @Test
    void leavesTeacherConversationAnswersWithoutIssues() {
        TutorModelDecision decision = client.parse("""
                {"type":"answer","content":"讲解 [依据1]。","issues":[
                  {"originalSentence":"I go to the park yesterday.","suggestion":"I went to the park yesterday.",
                   "category":"GRAMMAR","severity":"minor","explanation":"不属于教师对话。"}
                ]}
                """, TutorScenario.TEACHER_CONVERSATION);

        assertThat(((TutorModelDecision.Answer) decision).issues()).isEmpty();
    }
}
