package com.writelab.agent;

import com.writelab.tools.ToolInvocationStatus;

public enum AgentStepStatus {
    SUCCESS,
    NOT_FOUND,
    BLOCKED,
    FAILED,
    UNKNOWN_TOOL;

    public static AgentStepStatus from(ToolInvocationStatus status) {
        return switch (status) {
            case SUCCESS -> SUCCESS;
            case NOT_FOUND -> NOT_FOUND;
            case BLOCKED -> BLOCKED;
            case FAILED -> FAILED;
        };
    }
}
