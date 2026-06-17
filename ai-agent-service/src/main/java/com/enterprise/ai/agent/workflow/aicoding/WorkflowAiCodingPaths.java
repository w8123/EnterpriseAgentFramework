package com.enterprise.ai.agent.workflow.aicoding;

import java.util.regex.Pattern;

/**
 * Path matcher for Workflow AI Coding REST endpoints exposed to external AI tools.
 */
public final class WorkflowAiCodingPaths {

    private static final Pattern REQUEST_PATH = Pattern.compile(
            "^/api/workflows/(?:[^/]+/ai-coding(?:/.*)?|ai-coding/workflows)$");

    private WorkflowAiCodingPaths() {
    }

    public static boolean matchesRequestPath(String path) {
        return path != null && REQUEST_PATH.matcher(path).matches();
    }
}
