package com.writelab.feedback;

public interface FeedbackModel {

    ModelMode mode();

    FeedbackResult generate(String writingText);
}
