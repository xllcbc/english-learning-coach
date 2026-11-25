package com.writelab.conversation;

import com.writelab.agent.TutorBriefing;
import com.writelab.agent.TutorCitableSource;
import com.writelab.agent.TutorSourceKind;
import com.writelab.feedback.IssueCategoryLabels;
import com.writelab.feedback.LearningIssue;
import com.writelab.feedback.StructuredFeedback;
import com.writelab.retrieval.DocumentCitation;
import com.writelab.retrieval.ExplanationMaterialResolver;
import com.writelab.retrieval.GroundingMaterial;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the model-facing briefing for one Teacher Conversation Turn. Source numbering lives here
 * so the service, the offline model and the real adapter all read the same references.
 */
final class TeacherConversationBriefing {

    private TeacherConversationBriefing() {
    }

    static TutorBriefing create(
            TeacherConversationStore.PracticeSource practice,
            StructuredFeedback feedback,
            ExplanationMaterialResolver.ResolvedMaterials materials
    ) {
        StringBuilder text = new StringBuilder("这是一份已完成的 Practice Attempt。\n");
        text.append("写作原文：\n").append(practice.writingText()).append("\n\n");
        text.append("已记录的学习问题：\n");
        for (LearningIssue issue : feedback.focusedIssues()) {
            text.append("- [").append(issue.category()).append('/').append(issue.severity()).append("] ")
                    .append(issue.originalSentence()).append(" → ").append(issue.suggestion())
                    .append("；").append(issue.explanation()).append('\n');
        }

        List<TutorCitableSource> sources = new ArrayList<>();
        for (GroundingMaterial material : materials.groundingMaterials()) {
            sources.add(new TutorCitableSource(
                    sources.size() + 1,
                    TutorSourceKind.GROUNDING_MATERIAL,
                    IssueCategoryLabels.label(material.category()),
                    material.content(),
                    material.source(),
                    material.license()));
        }
        for (DocumentCitation document : materials.documentCitations()) {
            sources.add(new TutorCitableSource(
                    document.reference(),
                    TutorSourceKind.USER_DOCUMENT,
                    document.title(),
                    document.content(),
                    "我的文档",
                    null));
        }

        if (sources.isEmpty()) {
            text.append("\n（没有检索到可引用的依据材料。）\n");
        } else {
            text.append("\n可引用依据：\n");
            for (TutorCitableSource source : sources) {
                text.append("[依据").append(source.reference()).append("] ")
                        .append(source.kind().label()).append('：')
                        .append(source.title()).append(" · ").append(source.content()).append('\n');
            }
        }
        return new TutorBriefing(text.toString(), sources);
    }
}
