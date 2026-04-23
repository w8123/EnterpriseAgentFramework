package com.enterprise.ai.agent.agentscope.adapter;

import io.agentscope.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryAdapterTrimTest {

    @Test
    void onlyKeepsKnowledgeSearchBridgeAfterToolingTrim() {
        Set<String> toolNames = Arrays.stream(ToolRegistryAdapter.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .map(method -> method.getAnnotation(Tool.class).name())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of("search_knowledge"), toolNames);
        assertFalse(toolNames.contains("query_database"));
        assertFalse(toolNames.contains("call_business_api"));
        assertFalse(toolNames.contains("query_user_profile"));
        assertTrue(toolNames.contains("search_knowledge"));
    }
}
