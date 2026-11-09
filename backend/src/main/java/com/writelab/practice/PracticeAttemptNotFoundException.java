package com.writelab.practice;

import java.util.UUID;

public class PracticeAttemptNotFoundException extends RuntimeException {

    public PracticeAttemptNotFoundException(UUID id) {
        super("该练习记录不存在或已被删除。 (" + id + ")");
    }
}
