package com.enterprise.ai.control.a2a.application.task;

import com.enterprise.ai.control.a2a.domain.A2aTaskState;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public final class A2aTaskContracts {

    public record SendCommand(
            String tenant,
            String messageId,
            String contextId,
            String taskId,
            byte[] canonicalMessage,
            String payloadSha256,
            long payloadBytes,
            String safeSummary,
            String safeMetadataJson,
            String contentClassification,
            PartProfile parts,
            List<String> acceptedOutputModes,
            Integer historyLength,
            boolean returnImmediately,
            boolean pushNotificationRequested) {
        public SendCommand {
            canonicalMessage = canonicalMessage == null ? null : canonicalMessage.clone();
            acceptedOutputModes = acceptedOutputModes == null
                    ? List.of() : List.copyOf(acceptedOutputModes);
        }

        @Override
        public byte[] canonicalMessage() {
            return canonicalMessage == null ? null : canonicalMessage.clone();
        }
    }

    public record PartProfile(
            boolean containsText,
            boolean containsRaw,
            boolean containsUrl,
            boolean containsData,
            Set<String> mediaTypes) {
        public PartProfile {
            mediaTypes = mediaTypes == null ? Set.of() : Set.copyOf(mediaTypes);
        }
    }

    public record ListQuery(
            String tenant,
            String contextId,
            A2aTaskState state,
            int pageSize,
            int offset,
            int historyLength,
            LocalDateTime statusTimestampAfter,
            boolean includeArtifacts) {
    }

    public record TaskResource(
            String taskId,
            String contextId,
            A2aTaskState state,
            LocalDateTime statusTimestamp,
            String statusMessageSummary,
            String runtimeRunId,
            String traceId,
            String cancelPhase,
            List<String> historyCanonicalJson,
            List<String> artifactCanonicalJson) {
        public TaskResource {
            historyCanonicalJson = historyCanonicalJson == null
                    ? List.of() : List.copyOf(historyCanonicalJson);
            artifactCanonicalJson = artifactCanonicalJson == null
                    ? null : List.copyOf(artifactCanonicalJson);
        }
    }

    public record ListResult(
            List<TaskResource> tasks,
            long totalSize,
            int pageSize,
            int offset) {
        public ListResult {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }

    private A2aTaskContracts() {
    }
}
