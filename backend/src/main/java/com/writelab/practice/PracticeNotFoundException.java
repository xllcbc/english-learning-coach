package com.writelab.practice;

import java.util.UUID;

public class PracticeNotFoundException extends RuntimeException {

    public PracticeNotFoundException(UUID id) {
        super("Practice Attempt 不存在: " + id);
    }
}
