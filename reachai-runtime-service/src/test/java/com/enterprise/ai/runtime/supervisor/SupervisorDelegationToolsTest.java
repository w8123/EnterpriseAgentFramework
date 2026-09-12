package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter.RemoteAgentBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupervisorDelegationToolsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void remoteSchemaReflectsTheFixedBindingAndPreservesContinuationFields() {
        var tool = SupervisorDelegationTools.remoteAgent(binding("  订单查询  ",
                "[\" lookup \",\"lookup\",\"\",null]", "[\"application/json\"]"), json,
                ignored -> fail("Schema inspection must not execute a delegation"));
        var schema = json.valueToTree(tool.getParameters());
        assertEquals("remote_query", tool.getName());
        assertEquals("订单查询", tool.getDescription());
        assertEquals(json.valueToTree(List.of("text", "protocolSkillId")), schema.get("required"));
        assertFalse(schema.get("additionalProperties").asBoolean());
        assertEquals(json.valueToTree(List.of("lookup")), schema.at("/properties/protocolSkillId/enum"));
        assertEquals("application/json", schema.at("/properties/acceptedOutputModes/items/enum/0").asText());
        assertEquals(262_144, schema.at("/properties/text/maxLength").asInt());
        assertEquals("string", schema.at("/properties/contextId/type").asText());
        assertEquals("string", schema.at("/properties/taskId/type").asText());
    }

    @Test
    void wildcardBindingsDoNotBecomeLiteralEnumsAndFallbackDescriptionIsTrimmed() {
        var tool = SupervisorDelegationTools.remoteAgent(binding(" ", "[\"*\"]", "[\"*/*\"]"), json,
                ignored -> Mono.empty());
        var schema = json.valueToTree(tool.getParameters());
        assertTrue(schema.at("/properties/protocolSkillId/enum").isMissingNode());
        assertTrue(schema.at("/properties/acceptedOutputModes/items/enum").isMissingNode());
        assertEquals("Delegate a governed task to remote Agent orders", tool.getDescription());
    }

    @Test
    void malformedPublishedBindingIsRejectedBeforeAnyDelegation() {
        var calls = new AtomicInteger();
        var tool = SupervisorDelegationTools.remoteAgent(binding(null, "[broken", "[]"), json,
                ignored -> { calls.incrementAndGet(); return Mono.empty(); });
        var error = assertThrows(IllegalStateException.class, tool::getParameters);
        assertEquals("Published A2A binding contains invalid list JSON", error.getMessage());
        assertEquals(0, calls.get());
        assertEquals(List.of(), SupervisorDelegationTools.publishedList(json, "null"));
        assertEquals(List.of(), SupervisorDelegationTools.publishedList(json, " "));
    }

    @Test
    void managedSchemasSeparateCreationFromExistingExecutionReads() {
        var start = SupervisorDelegationTools.managedExecutor(ManagedExecutorAgentDelegationService.START_TOOL,
                ignored -> Mono.empty());
        var schema = json.valueToTree(start.getParameters());
        assertEquals(json.valueToTree(List.of("objective")), schema.get("required"));
        assertEquals(65_535, schema.at("/properties/objective/maxLength").asInt());
        assertFalse(schema.get("additionalProperties").asBoolean());
        for (String name : List.of(ManagedExecutorAgentDelegationService.STATUS_TOOL,
                ManagedExecutorAgentDelegationService.READ_RESULT_TOOL)) {
            var read = SupervisorDelegationTools.managedExecutor(name, ignored -> Mono.empty());
            var readSchema = json.valueToTree(read.getParameters());
            assertEquals(name, read.getName());
            assertEquals(json.valueToTree(List.of("executionId")), readSchema.get("required"));
            assertEquals("^mex_[A-Za-z0-9._:-]+$", readSchema.at("/properties/executionId/pattern").asText());
            assertEquals(128, readSchema.at("/properties/executionId/maxLength").asInt());
            assertTrue(readSchema.at("/properties/objective").isMissingNode());
            assertFalse(readSchema.get("additionalProperties").asBoolean());
        }
    }

    @Test
    void remoteCallbackPreservesInputAndReactiveResultWithoutSubscribingEarly() {
        Map<String, Object> input = Map.of("text", "继续查询", "protocolSkillId", "lookup", "taskId", "outbound-1");
        var param = mock(ToolCallParam.class);
        when(param.getInput()).thenReturn(input);
        var subscriptions = new AtomicInteger();
        var result = ToolResultBlock.text("等待远程任务");
        Mono<ToolResultBlock> publisher = Mono.fromSupplier(() -> { subscriptions.incrementAndGet(); return result; });
        var tool = SupervisorDelegationTools.remoteAgent(binding(null, "[]", "[]"), json, supplied -> {
            assertSame(input, supplied);
            return publisher;
        });
        assertSame(publisher, tool.callAsync(param));
        assertEquals(0, subscriptions.get());
        assertSame(result, publisher.block());
        assertEquals(1, subscriptions.get());
    }

    @Test
    void managedCallbackPreservesPolicyFailureWithoutRetryOrConversion() {
        Map<String, Object> input = Map.of("executionId", "mex_existing");
        var param = mock(ToolCallParam.class);
        when(param.getInput()).thenReturn(input);
        var failure = new IllegalStateException("permission denied");
        var calls = new AtomicInteger();
        var tool = SupervisorDelegationTools.managedExecutor(ManagedExecutorAgentDelegationService.STATUS_TOOL, supplied -> {
            assertSame(input, supplied);
            calls.incrementAndGet();
            return Mono.error(failure);
        });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> tool.callAsync(param).block()));
        assertEquals(1, calls.get());
    }

    private RemoteAgentBinding binding(String description, String skills, String outputs) {
        return new RemoteAgentBinding(1L, 2L, 3L, 4L, "orders  ", "remote_query", description,
                skills, outputs, "LOW", "orders:read", true);
    }
}
