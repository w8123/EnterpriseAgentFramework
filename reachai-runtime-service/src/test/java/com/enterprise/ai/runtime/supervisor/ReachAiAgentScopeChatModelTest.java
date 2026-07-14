package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReachAiAgentScopeChatModelTest {

    @Test
    void suppliesBothStructuredInputAndJsonContentRequiredByAgentScope2() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> arguments = Map.of("summary", "query", "steps", List.of("lookup"));
        var toolCalls = objectMapper.valueToTree(List.of(Map.of(
                "id", "call-1",
                "type", "function",
                "function", Map.of(
                        "name", "record_supervisor_plan",
                        "arguments", objectMapper.writeValueAsString(arguments)))));
        RuntimeModelServiceClient client = request -> new ModelChatResult(200, "success",
                new ModelChatData(null, "test", "test", new ModelUsage(1, 1, 2), null,
                        toolCalls, "tool_calls"));
        ReachAiAgentScopeChatModel model = new ReachAiAgentScopeChatModel("model-1", client, objectMapper);

        var response = model.stream(List.of(Msg.builder()
                        .name("user").role(MsgRole.USER).textContent("query").build()), List.of(), null)
                .blockFirst();
        ToolUseBlock call = (ToolUseBlock) response.getContent().get(0);

        assertEquals(Map.of("summary", "query", "steps", List.of("lookup")), call.getInput());
        assertEquals(objectMapper.valueToTree(arguments), objectMapper.readTree(call.getContent()));
    }
}
