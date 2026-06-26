package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.workflow.aicoding.pageassistant.WorkflowPageAssistantAiCodingService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowAiCodingAuthGuardTest {

    private static final Set<String> WORKFLOW_AI_CODING_PROTECTED = Set.of(
            "create",
            "getContext",
            "validate",
            "patch",
            "run",
            "getVersions",
            "publish",
            "listRuns",
            "getRunDetail");

    private static final Set<String> PAGE_ASSISTANT_AI_CODING_PROTECTED = Set.of(
            "getCatalog",
            "validate",
            "smokeTest");

    @Test
    void workflowAiCodingServicePublicEndpointsAreExplicitlyProtected() {
        assertEquals(WORKFLOW_AI_CODING_PROTECTED, publicMethodNames(WorkflowAiCodingService.class));
    }

    @Test
    void pageAssistantAiCodingServicePublicEndpointsAreExplicitlyProtected() {
        assertEquals(PAGE_ASSISTANT_AI_CODING_PROTECTED, publicMethodNames(WorkflowPageAssistantAiCodingService.class));
    }

    private Set<String> publicMethodNames(Class<?> type) {
        Set<String> names = new HashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            if (method.getDeclaringClass() != type) {
                continue;
            }
            names.add(method.getName());
        }
        return names;
    }
}
