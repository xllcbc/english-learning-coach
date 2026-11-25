package com.writelab.tools;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The shared tool registry. Workflows and agent scenarios both read from this one place; an agent
 * scenario narrows it with its own allowlist rather than registering duplicate tools.
 */
public final class ToolRegistry {

    private final Map<String, ToolDefinition> definitions;

    public ToolRegistry(Collection<? extends ToolDefinition> definitions) {
        Map<String, ToolDefinition> registered = new LinkedHashMap<>();
        for (ToolDefinition definition : definitions) {
            ToolDefinition previous = registered.putIfAbsent(definition.name(), definition);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + definition.name());
            }
        }
        this.definitions = Collections.unmodifiableMap(registered);
    }

    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    public boolean contains(String name) {
        return definitions.containsKey(name);
    }

    /**
     * The allowlisted tools that actually exist in this registry, sorted for a deterministic model
     * context and deterministic tests.
     */
    public Set<String> availableNames(Set<String> allowlist) {
        Set<String> available = new TreeSet<>();
        for (String name : allowlist) {
            if (definitions.containsKey(name)) {
                available.add(name);
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(available));
    }

    /**
     * Opens one session per allowlisted tool for the caller's turn. Registering a tool never gives a
     * scenario access to it; only the allowlist passed here does.
     */
    public Map<String, ToolSession> openSessions(Set<String> allowlist) {
        Map<String, ToolSession> sessions = new LinkedHashMap<>();
        for (String name : new TreeSet<>(allowlist)) {
            ToolDefinition definition = definitions.get(name);
            if (definition != null) {
                sessions.put(name, definition.openSession());
            }
        }
        return Collections.unmodifiableMap(sessions);
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(definitions.keySet()));
    }
}
