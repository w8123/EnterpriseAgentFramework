package com.enterprise.ai.runtime.execution;

import java.util.Map;

/**
 * GraphSpec 执行过程中的实时事件出口。事件必须在节点实际执行前后发出，禁止整图结束后重放。
 */
public interface RuntimeGraphSpecExecutionEventSink {

    RuntimeGraphSpecExecutionEventSink NOOP = new RuntimeGraphSpecExecutionEventSink() {
    };

    default void onNodeStarted(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
    }

    default void onNodeDelta(String nodeId, String nodeType, String text, Map<String, Object> safePayload) {
    }

    default void onNodeCompleted(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
    }

    default void onNodeWaiting(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
    }

    default void onNodeFailed(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
    }

    default void onExecutionCancelled(Map<String, Object> safePayload) {
    }
}
