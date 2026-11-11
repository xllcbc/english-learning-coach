package com.writelab.explanation;

import com.writelab.feedback.StructuredFeedback;
import com.writelab.retrieval.GroundingMaterial;
import java.util.List;

public record ExplanationRequest(
        String writingText,
        StructuredFeedback feedback,
        List<GroundingMaterial> groundingMaterials
) {
}
