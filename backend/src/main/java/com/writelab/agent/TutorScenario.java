package com.writelab.agent;

/**
 * The scenario an agent turn belongs to. The bounded core stays channel-agnostic; the scenario
 * only lets the model adapters and the offline model choose the right briefing rules.
 */
public enum TutorScenario {
    TEACHER_CONVERSATION,
    SPEAKING_PRACTICE
}
