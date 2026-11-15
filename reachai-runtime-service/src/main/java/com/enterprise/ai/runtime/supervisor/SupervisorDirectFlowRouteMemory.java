package com.enterprise.ai.runtime.supervisor;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second time without reasoning. When the model picked a direct flow for a question and the flow's
 * answer was delivered as-is, the question is remembered for that exact Agent configuration version.
 * An identical later question runs that flow straight away, skipping the model
 * round that only chose the tool.
 *
 * <p>This is a cache, not a source of truth: it lives in memory, is bounded, and starts empty after a
 * restart (the first repeat then goes through the model once and is remembered again). It never
 * widens what a run may do: the routed flow still has to be an admitted direct flow of the current
 * configuration, and it runs through the normal Workflow path with the caller's identity.</p>
 */
@Component
public class SupervisorDirectFlowRouteMemory {

    private static final int MAX_PER_CONFIG = 64;
    private static final int MAX_CONFIGS = 512;
    private static final int MAX_QUESTION_LENGTH = 200;

    private final Map<String, Deque<Entry>> entries = new ConcurrentHashMap<>();

    record Entry(String normalized, String toolName) { }

    /** Remembers a question whose direct-flow answer was delivered for this configuration version. */
    public void remember(String configVersionId, String question, String toolName) {
        String normalized = normalize(question);
        if (!StringUtils.hasText(configVersionId) || normalized.isEmpty() || !StringUtils.hasText(toolName)) return;
        if (entries.size() >= MAX_CONFIGS && !entries.containsKey(configVersionId)) {
            Iterator<String> oldest = entries.keySet().iterator();
            if (oldest.hasNext()) entries.remove(oldest.next());
        }
        Deque<Entry> list = entries.computeIfAbsent(configVersionId, ignored -> new ArrayDeque<>());
        synchronized (list) {
            list.removeIf(entry -> entry.normalized().equals(normalized));
            list.addFirst(new Entry(normalized, toolName.trim()));
            while (list.size() > MAX_PER_CONFIG) list.removeLast();
        }
    }

    /**
     * The direct-flow tool to run for this question, or null. Only tools in {@code allowedTools} (the
     * direct flows admitted for the current run) are returned, and only when exactly one tool matches.
     */
    public String match(String configVersionId, String question, Set<String> allowedTools) {
        if (!StringUtils.hasText(configVersionId) || allowedTools == null || allowedTools.isEmpty()) return null;
        Deque<Entry> list = entries.get(configVersionId);
        if (list == null) return null;
        String normalized = normalize(question);
        if (normalized.isEmpty()) return null;
        Set<String> matched = new HashSet<>();
        synchronized (list) {
            for (Entry entry : list) {
                if (allowedTools.contains(entry.toolName()) && normalized.equals(entry.normalized())) {
                    matched.add(entry.toolName());
                }
            }
        }
        return matched.size() == 1 ? matched.iterator().next() : null;
    }

    static String normalize(String question) {
        if (!StringUtils.hasText(question) || question.length() > MAX_QUESTION_LENGTH) return "";
        return question.trim();
    }
}
