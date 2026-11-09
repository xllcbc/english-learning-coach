package com.writelab.feedback;

import java.util.List;

public class InvalidFeedbackException extends RuntimeException {

    private final List<String> errors;

    public InvalidFeedbackException(List<String> errors) {
        super("结构化反馈校验失败");
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
