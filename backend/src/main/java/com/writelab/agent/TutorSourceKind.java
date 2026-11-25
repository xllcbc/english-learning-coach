package com.writelab.agent;

/**
 * The kinds of source a scenario can put in a briefing. Dictionary entries arrive through tool
 * observations instead, so they are not listed here.
 */
public enum TutorSourceKind {
    GROUNDING_MATERIAL("依据材料"),
    USER_DOCUMENT("我的文档"),
    LEARNING_ISSUE("学习历史");

    private final String label;

    TutorSourceKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
