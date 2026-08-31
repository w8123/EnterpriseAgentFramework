package com.enterprise.ai.control.a2a.application.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** Transport-neutral A2A 1.0 data model shared by inbound and outbound adapters. */
public final class A2aProtocolModels {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Part(
            String text,
            String raw,
            String url,
            JsonNode data,
            Map<String, JsonNode> metadata,
            String filename,
            String mediaType) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(
            String messageId,
            String contextId,
            String taskId,
            String role,
            List<Part> parts,
            Map<String, JsonNode> metadata,
            List<String> extensions,
            List<String> referenceTaskIds) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SendMessageConfiguration(
            List<String> acceptedOutputModes,
            JsonNode taskPushNotificationConfig,
            Integer historyLength,
            Boolean returnImmediately) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SendMessageRequest(
            String tenant,
            Message message,
            SendMessageConfiguration configuration,
            Map<String, JsonNode> metadata) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CancelTaskRequest(
            String tenant,
            String id,
            Map<String, JsonNode> metadata) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskStatus(
            String state,
            Message message,
            String timestamp) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Artifact(
            String artifactId,
            String name,
            String description,
            List<Part> parts,
            Map<String, JsonNode> metadata,
            List<String> extensions) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Task(
            String id,
            String contextId,
            TaskStatus status,
            List<Artifact> artifacts,
            List<Message> history,
            Map<String, JsonNode> metadata) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SendMessageResponse(Task task, Message message) {
    }

    public record ListTasksResponse(
            List<Task> tasks,
            String nextPageToken,
            int pageSize,
            long totalSize) {
        public ListTasksResponse {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
            nextPageToken = nextPageToken == null ? "" : nextPageToken;
        }
    }

    public record ErrorEnvelope(ErrorStatus error) {
    }

    public record ErrorStatus(
            int code,
            String status,
            String message,
            List<Map<String, Object>> details) {
        public ErrorStatus {
            details = details == null ? List.of() : List.copyOf(details);
        }
    }

    private A2aProtocolModels() {
    }
}
