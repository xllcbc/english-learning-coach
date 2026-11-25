package com.writelab.config;

import com.writelab.retrieval.LearningMaterialRetriever;
import com.writelab.retrieval.UserDocumentRetriever;
import com.writelab.tools.GeneratePracticePromptToolDefinition;
import com.writelab.tools.LookupWordTool;
import com.writelab.tools.LookupWordToolDefinition;
import com.writelab.tools.RetrieveGroundingMaterialToolDefinition;
import com.writelab.tools.SearchUserDocumentsToolDefinition;
import com.writelab.tools.ToolDefinition;
import com.writelab.tools.ToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Wires the shared tool registry. Every scenario reads from this registry and narrows it with its
 * own allowlist; adding a tool here must not give any scenario access without an explicit allowlist
 * entry.
 */
@Configuration
public class AgentConfiguration {

    @Bean
    LookupWordToolDefinition lookupWordToolDefinition(LookupWordTool lookupWordTool, ObjectMapper objectMapper) {
        return new LookupWordToolDefinition(lookupWordTool, objectMapper);
    }

    @Bean
    RetrieveGroundingMaterialToolDefinition retrieveGroundingMaterialToolDefinition(
            LearningMaterialRetriever learningMaterialRetriever,
            ObjectMapper objectMapper
    ) {
        return new RetrieveGroundingMaterialToolDefinition(learningMaterialRetriever, objectMapper);
    }

    @Bean
    SearchUserDocumentsToolDefinition searchUserDocumentsToolDefinition(
            UserDocumentRetriever userDocumentRetriever,
            ObjectMapper objectMapper
    ) {
        return new SearchUserDocumentsToolDefinition(userDocumentRetriever, objectMapper);
    }

    @Bean
    GeneratePracticePromptToolDefinition generatePracticePromptToolDefinition(ObjectMapper objectMapper) {
        return new GeneratePracticePromptToolDefinition(objectMapper);
    }

    @Bean
    ToolRegistry toolRegistry(List<ToolDefinition> definitions) {
        return new ToolRegistry(definitions);
    }
}
