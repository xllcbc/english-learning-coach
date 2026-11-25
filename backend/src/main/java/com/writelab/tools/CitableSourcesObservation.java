package com.writelab.tools;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one JSON shape for a tool result that produces citable sources. It mirrors the citation
 * snapshot the conversation persists, so the model can cite the number it just received and the
 * citation builder can rebuild the same source after the turn ends.
 */
final class CitableSourcesObservation {

    private CitableSourcesObservation() {
    }

    static ToolResult success(List<CitableToolSource> sources, ObjectMapper objectMapper) {
        if (sources.isEmpty()) {
            return new ToolResult(ToolInvocationStatus.NOT_FOUND, toJson("NOT_FOUND", sources, objectMapper));
        }
        return new ToolResult(ToolInvocationStatus.SUCCESS, toJson("SUCCESS", sources, objectMapper));
    }

    static ToolResult failed(String observation) {
        return new ToolResult(ToolInvocationStatus.FAILED, observation);
    }

    private static String toJson(
            String status,
            List<CitableToolSource> sources,
            ObjectMapper objectMapper
    ) {
        List<Map<String, Object>> payload = new ArrayList<>(sources.size());
        for (CitableToolSource source : sources) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reference", source.reference());
            item.put("kind", source.kind());
            item.put("title", source.title());
            item.put("content", source.content());
            item.put("source", source.source());
            item.put("license", source.license());
            payload.add(item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("status", status);
        root.put("sources", payload);
        return objectMapper.writeValueAsString(root);
    }

    record CitableToolSource(
            int reference,
            String kind,
            String title,
            String content,
            String source,
            String license
    ) {
    }
}
