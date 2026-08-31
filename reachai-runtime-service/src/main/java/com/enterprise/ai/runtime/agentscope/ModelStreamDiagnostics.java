package com.enterprise.ai.runtime.agentscope;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型流终态安全诊断。仅含计数与 usage，不含 reasoning/prompt/凭证原文。
 */
public final class ModelStreamDiagnostics {

    private final String modelInstanceId;
    private final String finishReason;
    private final int contentDeltaCount;
    private final int contentLength;
    private final int reasoningDeltaCount;
    private final int reasoningLength;
    private final int toolCallDeltaCount;
    private final int assembledToolCallCount;
    private final Integer promptTokens;
    private final Integer completionTokens;
    private final Integer reasoningTokens;
    private final Integer totalTokens;
    private final boolean consumedAnyEvent;
    private final boolean completedReceived;
    private final boolean emittedPublicContent;

    private ModelStreamDiagnostics(Builder builder) {
        this.modelInstanceId = builder.modelInstanceId;
        this.finishReason = builder.finishReason;
        this.contentDeltaCount = builder.contentDeltaCount;
        this.contentLength = builder.contentLength;
        this.reasoningDeltaCount = builder.reasoningDeltaCount;
        this.reasoningLength = builder.reasoningLength;
        this.toolCallDeltaCount = builder.toolCallDeltaCount;
        this.assembledToolCallCount = builder.assembledToolCallCount;
        this.promptTokens = builder.promptTokens;
        this.completionTokens = builder.completionTokens;
        this.reasoningTokens = builder.reasoningTokens;
        this.totalTokens = builder.totalTokens;
        this.consumedAnyEvent = builder.consumedAnyEvent;
        this.completedReceived = builder.completedReceived;
        this.emittedPublicContent = builder.emittedPublicContent;
    }

    public String modelInstanceId() {
        return modelInstanceId;
    }

    public String finishReason() {
        return finishReason;
    }

    public boolean consumedAnyEvent() {
        return consumedAnyEvent;
    }

    public boolean completedReceived() {
        return completedReceived;
    }

    public boolean emittedPublicContent() {
        return emittedPublicContent;
    }

    public Map<String, Object> toSafeMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "modelInstanceId", modelInstanceId);
        put(map, "finishReason", finishReason);
        map.put("contentDeltaCount", contentDeltaCount);
        map.put("contentLength", contentLength);
        map.put("reasoningDeltaCount", reasoningDeltaCount);
        map.put("reasoningLength", reasoningLength);
        map.put("toolCallDeltaCount", toolCallDeltaCount);
        map.put("assembledToolCallCount", assembledToolCallCount);
        map.put("consumedAnyEvent", consumedAnyEvent);
        map.put("completedReceived", completedReceived);
        map.put("emittedPublicContent", emittedPublicContent);
        Map<String, Object> usage = new LinkedHashMap<>();
        put(usage, "promptTokens", promptTokens);
        put(usage, "completionTokens", completionTokens);
        put(usage, "reasoningTokens", reasoningTokens);
        put(usage, "totalTokens", totalTokens);
        if (!usage.isEmpty()) {
            map.put("usage", usage);
        }
        Map<String, Object> eventCounts = new LinkedHashMap<>();
        eventCounts.put("contentDeltaCount", contentDeltaCount);
        eventCounts.put("reasoningDeltaCount", reasoningDeltaCount);
        eventCounts.put("toolCallDeltaCount", toolCallDeltaCount);
        eventCounts.put("assembledToolCallCount", assembledToolCallCount);
        map.put("eventCounts", eventCounts);
        return map;
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String modelInstanceId;
        private String finishReason;
        private int contentDeltaCount;
        private int contentLength;
        private int reasoningDeltaCount;
        private int reasoningLength;
        private int toolCallDeltaCount;
        private int assembledToolCallCount;
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer reasoningTokens;
        private Integer totalTokens;
        private boolean consumedAnyEvent;
        private boolean completedReceived;
        private boolean emittedPublicContent;

        public Builder modelInstanceId(String modelInstanceId) {
            this.modelInstanceId = modelInstanceId;
            return this;
        }

        public Builder finishReason(String finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public Builder contentDeltaCount(int contentDeltaCount) {
            this.contentDeltaCount = contentDeltaCount;
            return this;
        }

        public Builder contentLength(int contentLength) {
            this.contentLength = contentLength;
            return this;
        }

        public Builder reasoningDeltaCount(int reasoningDeltaCount) {
            this.reasoningDeltaCount = reasoningDeltaCount;
            return this;
        }

        public Builder reasoningLength(int reasoningLength) {
            this.reasoningLength = reasoningLength;
            return this;
        }

        public Builder toolCallDeltaCount(int toolCallDeltaCount) {
            this.toolCallDeltaCount = toolCallDeltaCount;
            return this;
        }

        public Builder assembledToolCallCount(int assembledToolCallCount) {
            this.assembledToolCallCount = assembledToolCallCount;
            return this;
        }

        public Builder promptTokens(Integer promptTokens) {
            this.promptTokens = promptTokens;
            return this;
        }

        public Builder completionTokens(Integer completionTokens) {
            this.completionTokens = completionTokens;
            return this;
        }

        public Builder reasoningTokens(Integer reasoningTokens) {
            this.reasoningTokens = reasoningTokens;
            return this;
        }

        public Builder totalTokens(Integer totalTokens) {
            this.totalTokens = totalTokens;
            return this;
        }

        public Builder consumedAnyEvent(boolean consumedAnyEvent) {
            this.consumedAnyEvent = consumedAnyEvent;
            return this;
        }

        public Builder completedReceived(boolean completedReceived) {
            this.completedReceived = completedReceived;
            return this;
        }

        public Builder emittedPublicContent(boolean emittedPublicContent) {
            this.emittedPublicContent = emittedPublicContent;
            return this;
        }

        public ModelStreamDiagnostics build() {
            return new ModelStreamDiagnostics(this);
        }
    }
}
