package com.writelab.explanation;

import java.util.function.Consumer;

public interface ExplanationModel {

    void streamExplanation(ExplanationRequest request, Consumer<String> onDelta);
}
