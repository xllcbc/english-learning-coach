package com.writelab.agent;

import com.writelab.tools.QueryLearningProgressToolDefinition;
import com.writelab.tools.RetrieveGroundingMaterialToolDefinition;
import com.writelab.tools.SearchUserDocumentsToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads citable sources out of the successful Agent Steps of tools that produce them (Grounding
 * Material, User Document and recurring Learning Issue results). The observation is the same JSON
 * the citation snapshot persists, so the model, replay and the citation builder all speak one shape
 * instead of three slightly different ones.
 */
public final class TutorToolSources {

    private static final Logger log = LoggerFactory.getLogger(TutorToolSources.class);

    private static final Set<String> CITABLE_TOOLS = Set.of(
            RetrieveGroundingMaterialToolDefinition.NAME,
            SearchUserDocumentsToolDefinition.NAME,
            QueryLearningProgressToolDefinition.NAME);

    private TutorToolSources() {
    }

    public static List<TutorCitableSource> successful(List<AgentStep> steps, ObjectMapper objectMapper) {
        List<TutorCitableSource> sources = new ArrayList<>();
        for (AgentStep step : steps) {
            if (!step.isToolCall()
                    || step.status() != AgentStepStatus.SUCCESS
                    || !CITABLE_TOOLS.contains(step.toolName())) {
                continue;
            }
            sources.addAll(read(step, objectMapper));
        }
        return List.copyOf(sources);
    }

    private static List<TutorCitableSource> read(AgentStep step, ObjectMapper objectMapper) {
        Map<String, Object> observation = AgentStepObservations.json(step, objectMapper);
        Object rawSources = observation.get("sources");
        if (!(rawSources instanceof List<?> items)) {
            return List.of();
        }

        List<TutorCitableSource> sources = new ArrayList<>(items.size());
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            try {
                sources.add(new TutorCitableSource(
                        integer(map.get("reference")),
                        TutorSourceKind.valueOf(String.valueOf(map.get("kind"))),
                        string(map.get("title")),
                        string(map.get("content")),
                        string(map.get("source")),
                        map.get("license") == null ? null : map.get("license").toString()));
            } catch (RuntimeException malformed) {
                log.warn("无法读取检索工具的可引用来源: tool={}, cause={}",
                        step.toolName(), malformed.getClass().getSimpleName());
            }
        }
        return List.copyOf(sources);
    }

    private static int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }
}
