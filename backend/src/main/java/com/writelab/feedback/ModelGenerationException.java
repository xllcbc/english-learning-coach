package com.writelab.feedback;

import java.util.List;

/**
 * Thrown when the real model still fails validation after the initial attempt and two retries.
 * Carries the specific errors from the final attempt for traceable logging.
 */
public class ModelGenerationException extends RuntimeException {

    private final List<String> errors;

    public ModelGenerationException(List<String> errors) {
        super("真实模型输出在重试后仍未通过校验");
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
