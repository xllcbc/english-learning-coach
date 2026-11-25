package com.writelab.targeted;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TargetedPracticeConfiguration {

    @Bean
    TargetedPracticeWorkflow targetedPracticeWorkflow() {
        return new TargetedPracticeWorkflow();
    }
}
