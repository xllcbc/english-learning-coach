package com.writelab.feedback;

public interface FeedbackModel {

    ModelMode mode();

    StructuredFeedback generate(String writingText);
}
