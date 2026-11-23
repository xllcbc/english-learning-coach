package com.writelab.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableRetrievalProperties.class);

    @Test
    void defaultsTheSimilarityThresholdToTheTunedValueWhenNotConfigured() {
        runner.run(context -> assertThat(context.getBean(RetrievalProperties.class)
                .groundingSimilarityThreshold()).isEqualTo(0.6));
    }

    @Test
    void defaultsTheUserDocumentBudgetIndependentlyFromGroundingMaterial() {
        runner.run(context -> {
            RetrievalProperties properties = context.getBean(RetrievalProperties.class);
            assertThat(properties.userDocumentMaxHits()).isEqualTo(4);
            assertThat(properties.userDocumentPerDocumentHits()).isEqualTo(2);
            assertThat(properties.userDocumentSimilarityThreshold()).isEqualTo(0.55);
            assertThat(properties.groundingMaxHits()).isEqualTo(6);
            assertThat(properties.groundingSimilarityThreshold()).isEqualTo(0.6);
        });
    }

    @EnableConfigurationProperties(RetrievalProperties.class)
    static class EnableRetrievalProperties {
    }
}
