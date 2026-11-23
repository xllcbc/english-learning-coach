package com.writelab.explanation;

import com.writelab.feedback.StructuredFeedback;
import com.writelab.retrieval.DocumentCitation;
import com.writelab.retrieval.GroundingMaterial;
import java.util.List;

public record ExplanationRequest(
        String writingText,
        StructuredFeedback feedback,
        List<GroundingMaterial> groundingMaterials,
        List<DocumentCitation> documentCitations
) {

    /**
     * Keeps the Grounding Material-only construction used by existing callers and tests.
     */
    public ExplanationRequest(
            String writingText,
            StructuredFeedback feedback,
            List<GroundingMaterial> groundingMaterials
    ) {
        this(writingText, feedback, groundingMaterials, List.of());
    }
}
