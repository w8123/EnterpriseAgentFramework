package com.enterprise.ai.agent.platform.control.context.runtime;

import com.enterprise.ai.agent.platform.control.context.ContextItemResponse;
import com.enterprise.ai.agent.platform.control.context.ContextItemType;
import com.enterprise.ai.agent.platform.control.context.ContextPackageResponse;
import com.enterprise.ai.agent.platform.control.context.ContextSearchResult;
import com.enterprise.ai.agent.platform.control.context.MemoryLane;
import com.enterprise.ai.agent.runtime.AgentRuntimeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeContextPromptFormatterTest {

    private RuntimeContextPromptFormatter formatter;

    @BeforeEach
    void setUp() {
        RuntimeContextProperties properties = new RuntimeContextProperties();
        properties.setInjectUserMemory(true);
        properties.setInjectPageContext(true);
        properties.setInjectWorkflowContext(true);
        properties.setInjectApiContext(true);
        properties.setInjectRules(true);
        formatter = new RuntimeContextPromptFormatter(properties);
    }

    @Test
    void formatterDoesNotOutputProjectMemory() {
        ContextItemResponse projectItem = ContextItemResponse.builder()
                .itemType(ContextItemType.FACT.name())
                .memoryLane(MemoryLane.PROJECT_DEV.name())
                .content("project secret")
                .build();
        ContextItemResponse userItem = ContextItemResponse.builder()
                .itemType(ContextItemType.PREFERENCE.name())
                .memoryLane(MemoryLane.RUNTIME_USER.name())
                .summary("prefers compact UI")
                .sourceType("USER_CONFIRMED")
                .trustLevel("HIGH")
                .confidence(new BigDecimal("0.9000"))
                .build();

        ContextPackageResponse pkg = ContextPackageResponse.builder()
                .memoryLane(MemoryLane.RUNTIME_USER.name())
                .projectMemory(List.of(ContextSearchResult.builder().item(projectItem).rankScore(1).build()))
                .userMemory(List.of(ContextSearchResult.builder().item(userItem).rankScore(1).build()))
                .pageContext(List.of())
                .workflowContext(List.of())
                .apiContext(List.of())
                .rules(List.of())
                .build();

        String prompt = formatter.format(pkg);

        assertNotNull(prompt);
        assertFalse(prompt.contains("project secret"));
        assertTrue(prompt.contains("prefers compact UI"));
        assertTrue(prompt.contains("[ReachAI Runtime Context]"));
    }

    @Test
    void formatterIncludesTrustConfidenceAndSource() {
        ContextItemResponse item = ContextItemResponse.builder()
                .itemType(ContextItemType.PAGE_CONTEXT.name())
                .summary("orders page filters")
                .sourceType("MANUAL")
                .trustLevel("MEDIUM")
                .confidence(new BigDecimal("0.7000"))
                .build();
        ContextPackageResponse pkg = ContextPackageResponse.builder()
                .pageContext(List.of(ContextSearchResult.builder().item(item).rankScore(1).build()))
                .userMemory(List.of())
                .workflowContext(List.of())
                .apiContext(List.of())
                .rules(List.of())
                .build();

        String prompt = formatter.format(pkg);

        assertNotNull(prompt);
        assertTrue(prompt.contains("trust=MEDIUM"));
        assertTrue(prompt.contains("confidence=0.7000"));
        assertTrue(prompt.contains("source=MANUAL"));
    }

    @Test
    void emptyPackageReturnsNullPrompt() {
        ContextPackageResponse pkg = ContextPackageResponse.builder()
                .userMemory(List.of())
                .pageContext(List.of())
                .workflowContext(List.of())
                .apiContext(List.of())
                .rules(List.of())
                .build();

        assertNull(formatter.format(pkg));
    }

    @Test
    void agentRuntimeRequestKeepsOriginalMessageWhileUsingEffectiveMessageForModel() {
        RuntimeContextInjectionResult runtimeContext = RuntimeContextInjectionResult.builder()
                .enabled(true)
                .promptSection("[ReachAI Runtime Context]\n- Preference: dark mode")
                .build();
        AgentRuntimeRequest request = AgentRuntimeRequest.builder()
                .message("hello")
                .runtimeContext(runtimeContext)
                .build();

        assertEquals("hello", request.getMessage());
        assertTrue(request.effectiveUserMessage().contains("[ReachAI Runtime Context]"));
        assertTrue(request.effectiveUserMessage().contains("[User Message]"));
        assertTrue(request.effectiveUserMessage().endsWith("hello"));
    }
}
