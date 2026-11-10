package com.writelab.feedback;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record LearningIssue(
        @JsonPropertyDescription("从学习者原文中摘录的英文句子，保持原样。")
        String originalSentence,
        @JsonPropertyDescription("originalSentence 的最小英文修改稿：必须是可直接替换原句的完整英文句子，"
                + "只修复本条问题涉及的部分，不要写成 Consider…/Try… 之类的指导语。")
        String suggestion,
        IssueCategory category,
        IssueSeverity severity,
        @JsonPropertyDescription("面向中文母语学习者的中文讲解，说明问题原因与修改理由。")
        String explanation
) {
}
