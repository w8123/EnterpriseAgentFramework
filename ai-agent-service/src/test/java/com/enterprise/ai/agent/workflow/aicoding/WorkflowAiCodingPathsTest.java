package com.enterprise.ai.agent.workflow.aicoding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowAiCodingPathsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/workflows/ai-coding/workflows",
            "/api/workflows/wf-1/ai-coding/context",
            "/api/workflows/wf-1/ai-coding/patch",
            "/api/workflows/wf-1/ai-coding/validate",
            "/api/workflows/wf-1/ai-coding/run",
            "/api/workflows/wf-1/ai-coding/versions",
            "/api/workflows/wf-1/ai-coding/runs",
            "/api/workflows/wf-1/ai-coding/runs/trace-1",
            "/api/workflows/wf-1/ai-coding/page-assistant/catalog",
            "/api/workflows/wf-1/ai-coding/page-assistant/smoke-test"
    })
    void matchesWorkflowAiCodingPaths(String path) {
        assertTrue(WorkflowAiCodingPaths.matchesRequestPath(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/workflows/studio/generate-draft",
            "/api/workflows/wf-1/studio/edit-draft",
            "/api/workflows/wf-1/ai-coding-extra/context",
            "/api/workflows/ai-coding/workflows/extra",
            "/api/workflows//ai-coding/context",
            "/api/agents"
    })
    void rejectsNonWorkflowAiCodingPaths(String path) {
        assertFalse(WorkflowAiCodingPaths.matchesRequestPath(path));
    }

    @Test
    void rejectsNullPath() {
        assertFalse(WorkflowAiCodingPaths.matchesRequestPath(null));
    }
}
