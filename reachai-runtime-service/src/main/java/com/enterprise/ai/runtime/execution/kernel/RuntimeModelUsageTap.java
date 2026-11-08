package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.trace.usage.RuntimeModelUsageRecorder;

import java.util.Map;

/** Workflow model nodes' model calls, with each call's tokens kept against the root run (supervisor trace first). */
final class RuntimeModelUsageTap {

    private final RuntimeModelServiceClient client;
    private volatile RuntimeModelUsageRecorder recorder;

    RuntimeModelUsageTap(RuntimeModelServiceClient client) {
        this.client = client;
    }

    void setRecorder(RuntimeModelUsageRecorder recorder) {
        this.recorder = recorder;
    }

    ModelChatResult chat(Map<String, Object> context, ModelChatRequest request) {
        ModelChatResult result = client.chat(request);
        RuntimeModelUsageRecorder current = recorder;
        ModelChatData data = result == null ? null : result.getData();
        if (current != null && data != null && data.getUsage() != null) {
            current.record(traceId(context), RuntimeModelUsageRecorder.SOURCE_WORKFLOW_NODE,
                    request.getModelInstanceId(), data.getUsage().getPromptTokens(),
                    data.getUsage().getCompletionTokens());
        }
        return result;
    }

    private static String traceId(Map<String, Object> context) {
        for (String key : new String[]{"supervisorTraceId", "traceId"}) {
            Object value = context == null ? null : context.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value).trim();
        }
        return null;
    }
}
