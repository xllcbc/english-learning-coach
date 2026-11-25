package com.writelab.config;

import com.writelab.tools.LookupWordTool;
import com.writelab.tools.LookupWordToolDefinition;
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
    ToolRegistry toolRegistry(List<ToolDefinition> definitions) {
        return new ToolRegistry(definitions);
    }
}
