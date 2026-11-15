package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

/**
 * Direct flow: a verified read-only fixed Workflow whose published ANSWER node declares that its text
 * is already the reply for the user. The Supervisor then delivers that text as-is and ends the run,
 * instead of spending further model rounds on a plan and a rewritten answer.
 */
final class SupervisorDirectFlowAnswer {
    static final String CONFIG_KEY = "directAnswer";
    static final int MAX_ANSWER_LENGTH = 8000;

    private SupervisorDirectFlowAnswer() { }

    /** True when at least one ANSWER node of the published graph declares direct delivery. */
    static boolean declared(String graphJson, ObjectMapper mapper) {
        if (!StringUtils.hasText(graphJson) || mapper == null) return false;
        try {
            for (JsonNode node : mapper.readTree(graphJson).path("nodes")) {
                if (directAnswerNode(node)) return true;
            }
        } catch (Exception unreadable) {
            // An unreadable published snapshot cannot establish a direct-delivery contract.
        }
        return false;
    }

    /** The text to deliver, or null when this result did not end at an ANSWER node that declares it. */
    static String from(RuntimeGraphSpecExecutionResult result, String graphJson, ObjectMapper mapper) {
        if (result == null || !result.success() || result.uiRequest() != null
                || !"ANSWER".equals(result.nodeType()) || !StringUtils.hasText(result.nodeId())
                || !StringUtils.hasText(result.answer()) || result.answer().length() > MAX_ANSWER_LENGTH
                || !StringUtils.hasText(graphJson) || mapper == null) return null;
        try {
            for (JsonNode node : mapper.readTree(graphJson).path("nodes")) {
                if (result.nodeId().equals(node.path("id").asText())) {
                    return directAnswerNode(node) ? result.answer() : null;
                }
            }
        } catch (Exception unreadable) {
            // Same as above: no readable contract, no direct delivery.
        }
        return null;
    }

    private static boolean directAnswerNode(JsonNode node) {
        if (!"ANSWER".equalsIgnoreCase(node.path("type").asText("").trim())) return false;
        JsonNode config = node.path("config");
        return config.path(CONFIG_KEY).asBoolean(false)
                || config.path("answerConfig").path(CONFIG_KEY).asBoolean(false);
    }
}
