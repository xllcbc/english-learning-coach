package com.writelab.explanation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExplanationConfig {

    @Bean(name = "explanationExecutor", destroyMethod = "shutdown")
    ExecutorService explanationExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
