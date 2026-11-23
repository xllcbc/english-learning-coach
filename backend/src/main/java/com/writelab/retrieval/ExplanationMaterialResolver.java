package com.writelab.retrieval;

import com.writelab.feedback.StructuredFeedback;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * The explanation-side retrieval orchestration: Grounding Material first, then User Documents, with
 * one unified [依据N] numbering across both lists. A User Document failure is isolated here so the
 * Grounding Material result and the explanation still complete; the skip reason is persisted as a
 * Retrieval Hit.
 */
public class ExplanationMaterialResolver {

    private static final Logger log = LoggerFactory.getLogger(ExplanationMaterialResolver.class);

    private final GroundingMaterialResolver groundingMaterialResolver;
    private final UserDocumentRetriever userDocumentRetriever;
    private final EmbeddingClient embeddingClient;

    public ExplanationMaterialResolver(
            GroundingMaterialResolver groundingMaterialResolver,
            UserDocumentRetriever userDocumentRetriever
    ) {
        this(groundingMaterialResolver, userDocumentRetriever, null);
    }

    public ExplanationMaterialResolver(
            GroundingMaterialResolver groundingMaterialResolver,
            UserDocumentRetriever userDocumentRetriever,
            EmbeddingClient embeddingClient
    ) {
        this.groundingMaterialResolver = groundingMaterialResolver;
        this.userDocumentRetriever = userDocumentRetriever;
        this.embeddingClient = embeddingClient;
    }

    public ResolvedMaterials resolve(StructuredFeedback feedback) {
        String query = FocusedIssueQuery.text(feedback);
        float[] queryVector = null;
        boolean queryEmbeddingFailed = false;
        if (StringUtils.hasText(query) && embeddingClient != null) {
            // One Teacher Explanation builds one query vector. Both corpora below must reuse it:
            // independent embedding calls would double the online cost and could rank the same
            // query text differently.
            try {
                queryVector = EmbeddingVectors.requireSingle(embeddingClient.embed(List.of(query)));
            } catch (RuntimeException exception) {
                log.warn("共享查询向量构建失败，本次讲解不引用检索材料: cause={}",
                        exception.getClass().getSimpleName());
                queryEmbeddingFailed = true;
            }
        }

        GroundingMaterialResolver.GroundingResult grounding =
                groundingMaterialResolver.resolve(feedback, queryVector, queryEmbeddingFailed);

        if (!StringUtils.hasText(query)) {
            return new ResolvedMaterials(grounding.materials(), List.of(), grounding.hits());
        }

        List<RankedDocumentChunk> rankedDocuments;
        String skipReason = null;
        if (queryEmbeddingFailed) {
            rankedDocuments = List.of();
            skipReason = RetrievalHit.RETRIEVAL_FAILED;
        } else {
            try {
                rankedDocuments = userDocumentRetriever.retrieve(query, queryVector);
            } catch (RuntimeException exception) {
                // The document corpus is an optional grounding source; a failure must not cost the
                // learner the whole Teacher Explanation or the Grounding Material citations.
                log.warn("用户文档检索失败，本次讲解不引用文档: cause={}",
                        exception.getClass().getSimpleName());
                rankedDocuments = List.of();
                skipReason = RetrievalHit.RETRIEVAL_FAILED;
            }
        }

        List<RetrievalHit> hits = new ArrayList<>(grounding.hits());
        if (rankedDocuments.isEmpty()) {
            hits.add(RetrievalHit.userDocumentSkipped(
                    skipReason == null ? RetrievalHit.NO_USER_DOCUMENT : skipReason));
            return new ResolvedMaterials(grounding.materials(), List.of(), hits);
        }

        String ordering = queryVector == null
                ? RetrievalHit.TRIGRAM_SIMILARITY
                : RetrievalHit.VECTOR_SIMILARITY;
        List<DocumentCitation> citations = new ArrayList<>(rankedDocuments.size());
        int reference = grounding.materials().size() + 1;
        for (int index = 0; index < rankedDocuments.size(); index++) {
            RankedDocumentChunk chunk = rankedDocuments.get(index);
            citations.add(new DocumentCitation(
                    reference, chunk.documentId(), chunk.documentChunkId(), chunk.title(), chunk.content(),
                    false));
            hits.add(RetrievalHit.userDocument(chunk, index + 1, reference, ordering));
            reference++;
        }
        return new ResolvedMaterials(grounding.materials(), List.copyOf(citations), hits);
    }

    /**
     * Both citation lists plus every Retrieval Hit the explanation must persist. Document references
     * continue after the Grounding Material references, so the prompt, SSE payload and stored hits
     * all use one numbering.
     */
    public record ResolvedMaterials(
            List<GroundingMaterial> groundingMaterials,
            List<DocumentCitation> documentCitations,
            List<RetrievalHit> hits
    ) {
    }
}
