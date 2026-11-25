package com.writelab.targeted;

import java.util.UUID;

public class TargetedPracticeNotFoundException extends RuntimeException {

    public TargetedPracticeNotFoundException(UUID id) {
        super("Targeted Practice 不存在: " + id);
    }
}
