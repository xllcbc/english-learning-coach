package com.writelab.review;

import com.writelab.retrieval.LearningMaterialRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LearningReviewConfiguration {

    @Bean
    LearningReviewWorkflow learningReviewWorkflow(LearningMaterialRetriever learningMaterialRetriever) {
        return new LearningReviewWorkflow(learningMaterialRetriever);
    }
}
