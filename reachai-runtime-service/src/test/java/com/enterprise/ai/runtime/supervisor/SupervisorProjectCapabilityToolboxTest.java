package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.client.control.RuntimeAssistantCapabilityClient;
import com.enterprise.ai.runtime.client.control.RuntimeAssistantCapabilityClient.CallRequest;
import com.enterprise.ai.runtime.client.control.RuntimeAssistantCapabilityClient.CallResult;
import com.enterprise.ai.runtime.client.control.RuntimeAssistantCapabilityClient.SearchRequest;
import com.enterprise.ai.runtime.client.control.RuntimeAssistantCapabilityClient.SearchResult;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupervisorProjectCapabilityToolboxTest {

    private final RuntimeAssistantCapabilityClient client = mock(RuntimeAssistantCapabilityClient.class);
    private final SupervisorProjectCapabilityToolbox toolbox =
            new SupervisorProjectCapabilityToolbox(client, new ObjectMapper());
    private final RecordingHost host = new RecordingHost();
    private final WorkflowExecutionIdentity employee =
            WorkflowExecutionIdentity.fromEmbedSession("default", 1L, "yudao-erp", "1");

    @Test
    void opensOnlyForAVerifiedBusinessEmployeeOnAConfigurationThatAllowsIt() {
        assertNotNull(toolbox.open(config("PROJECT_CAPABILITIES"), employee, false, host));
        assertNull(toolbox.open(config("ALLOW_LIST"), employee, false, host));
        assertNull(toolbox.open(config("PROJECT_CAPABILITIES"), employee, true, host));
        assertNull(toolbox.open(config("PROJECT_CAPABILITIES"),
                WorkflowExecutionIdentity.fromAgent("default", 1L, "yudao-erp", "console-admin"), false, host));
        assertNull(toolbox.open(config("PROJECT_CAPABILITIES"),
                WorkflowExecutionIdentity.fromAutomation("default", 1L, "yudao-erp", "job-1"), false, host));
        assertNull(toolbox.open(config("PROJECT_CAPABILITIES"), null, false, host));
    }

    @Test
    void evaluationUsesTheDesignatedAccountAndNeverCallsAWrite() {
        assertNull(toolbox.openEvaluation(config("ALLOW_LIST"), "yudao-erp", null, "eval", host));
        assertNull(toolbox.openEvaluation(config("PROJECT_CAPABILITIES"), "yudao-erp", null, " ", host));
        when(client.search(any())).thenReturn(new SearchResult(true, null, 2, List.of(
                Map.of("name", "erpSaleOrderPage", "riskLevel", "READ"),
                Map.of("name", "erpCustomerCreate", "riskLevel", "WRITE"))));
        when(client.call(any())).thenReturn(new CallResult(true, false, Map.of("total", 2), null, null, null, null));
        var session = toolbox.openEvaluation(config("PROJECT_CAPABILITIES"), "yudao-erp", "1", "eval-01", host);
        assertTrue(session.evaluationReadOnly());

        session.invoke(SupervisorProjectCapabilityToolbox.SEARCH_TOOL, Map.of("query", "订单"));
        ToolResultBlock write = session.invoke(SupervisorProjectCapabilityToolbox.CALL_TOOL,
                Map.of("name", "erpCustomerCreate", "arguments", Map.of("name", "x")));
        ToolResultBlock read = session.invoke(SupervisorProjectCapabilityToolbox.CALL_TOOL,
                Map.of("name", "erpSaleOrderPage"));

        assertTrue(text(write).contains("read-only"));
        assertFalse(text(read).contains("read-only"));
        ArgumentCaptor<SearchRequest> search = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(search.capture());
        assertEquals("eval-01", search.getValue().externalUserId());
        assertEquals(Boolean.TRUE, search.getValue().readOnly());
        ArgumentCaptor<CallRequest> call = ArgumentCaptor.forClass(CallRequest.class);
        verify(client, times(1)).call(call.capture());
        assertEquals("erpSaleOrderPage", call.getValue().toolName());
        assertEquals(Boolean.TRUE, call.getValue().readOnly());
        assertEquals(List.of("erpSaleOrderPage"), session.calledNames());
    }

    @Test
    void searchAndCallUseTheVerifiedEmployeeNeverModelSuppliedIdentity() {
        when(client.search(any())).thenReturn(new SearchResult(true, null, 4, List.of(
                Map.of("name", "erpSaleOrderPage", "description", "分页查询销售订单", "riskLevel", "READ"))));
        when(client.call(any())).thenReturn(new CallResult(true, false, Map.of("total", 2), null, null, "9", "mcp_t"));
        var session = toolbox.open(config("PROJECT_CAPABILITIES"), employee, false, host);

        ToolResultBlock found = session.invoke(SupervisorProjectCapabilityToolbox.SEARCH_TOOL,
                Map.of("query", "销售订单", "externalUserId", "2", "projectCode", "other"));
        ToolResultBlock called = session.invoke(SupervisorProjectCapabilityToolbox.CALL_TOOL,
                Map.of("name", "erpSaleOrderPage", "arguments", Map.of("pageNo", 1)));

        assertTrue(text(found).contains("erpSaleOrderPage"));
        assertTrue(text(called).contains("\"total\":2"));
        ArgumentCaptor<SearchRequest> search = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(search.capture());
        assertEquals("1", search.getValue().externalUserId());
        assertEquals("yudao-erp", search.getValue().projectCode());
        ArgumentCaptor<CallRequest> call = ArgumentCaptor.forClass(CallRequest.class);
        verify(client).call(call.capture());
        assertEquals("1", call.getValue().externalUserId());
        assertEquals(Map.of("pageNo", 1), call.getValue().arguments());
        assertEquals(1, session.searchCount());
        assertEquals(1, session.callCount());
        assertEquals(List.of("erpSaleOrderPage"), session.calledNames());
        // The trace keeps the capability and argument names, never argument values.
        assertEquals(List.of("find_business_capabilities", "erpSaleOrderPage"), host.tracedTools);
        assertFalse(host.tracedInputs.toString().contains("pageNo=1"));
    }

    @Test
    void aWriteWaitingForApprovalIsReportedAsPendingNotDone() {
        when(client.call(any())).thenReturn(new CallResult(true, true,
                Map.of("approvalId", 5, "approvalStatus", "PENDING"), null, null, null, null));
        var session = toolbox.open(config("PROJECT_CAPABILITIES"), employee, false, host);

        ToolResultBlock result = session.invoke(SupervisorProjectCapabilityToolbox.CALL_TOOL,
                Map.of("name", "erpCustomerCreate", "arguments", Map.of("name", "某公司")));

        assertTrue(text(result).contains("\"approvalPending\":true"));
        assertEquals(1, session.approvalPendingCount());
    }

    @Test
    void aFailedWriteIsNeverRetriedButAFailedReadMayBe() {
        when(client.search(any())).thenReturn(new SearchResult(true, null, 2, List.of(
                Map.of("name", "erpStockPage", "riskLevel", "READ"),
                Map.of("name", "erpCustomerCreate", "riskLevel", "WRITE"))));
        when(client.call(any())).thenThrow(new IllegalStateException("timeout"));
        var session = toolbox.open(config("PROJECT_CAPABILITIES"), employee, false, host);
        session.invoke(SupervisorProjectCapabilityToolbox.SEARCH_TOOL, Map.of("query", "库存 客户"));

        session.invoke(SupervisorProjectCapabilityToolbox.CALL_TOOL, Map.of("name", "erpCustomerCreate"));
        ToolResultBlock secondWrite = session.invoke(SupervisorProjectCapabilityToolbox.CALL_TOOL,
                Map.of("name", "erpCustomerCreate"));
        session.invoke(SupervisorProjectCapabilityToolbox.CALL_TOOL, Map.of("name", "erpStockPage"));
        session.invoke(SupervisorProjectCapabilityToolbox.CALL_TOOL, Map.of("name", "erpStockPage"));

        assertTrue(text(secondWrite).contains("do not retry"));
        // One write attempt and two read attempts reached Control; the repeated write did not.
        verify(client, times(3)).call(any());
    }

    @Test
    void nothingRunsWhileAnotherActionWaitsForTheUser() {
        var session = toolbox.open(config("PROJECT_CAPABILITIES"), employee, false, host);
        host.blocked = "An execution tool is waiting for user confirmation; do not call another tool";

        ToolResultBlock result = session.invoke(SupervisorProjectCapabilityToolbox.CALL_TOOL,
                Map.of("name", "erpStockPage"));

        assertTrue(text(result).contains("waiting for user confirmation"));
        verify(client, times(0)).call(any());
    }

    @Test
    void callsAndSearchesAreBounded() {
        when(client.search(any())).thenReturn(new SearchResult(true, null, 0, List.of()));
        when(client.call(any())).thenReturn(new CallResult(true, false, Map.of(), null, null, null, null));
        var session = toolbox.open(config("PROJECT_CAPABILITIES"), employee, false, host);

        for (int index = 0; index < SupervisorProjectCapabilityToolbox.MAX_SEARCHES + 2; index++) {
            session.invoke(SupervisorProjectCapabilityToolbox.SEARCH_TOOL, Map.of("query", "q" + index));
        }
        for (int index = 0; index < SupervisorProjectCapabilityToolbox.MAX_CALLS + 2; index++) {
            session.invoke(SupervisorProjectCapabilityToolbox.CALL_TOOL, Map.of("name", "erpStockPage"));
        }

        verify(client, times(SupervisorProjectCapabilityToolbox.MAX_SEARCHES)).search(any());
        verify(client, times(SupervisorProjectCapabilityToolbox.MAX_CALLS)).call(any());
    }

    private static RuntimeAgentConfigSnapshot config(String mode) {
        return RuntimeAgentConfigSnapshot.builder().id(1L).toolCatalogMode(mode).build();
    }

    private static String text(ToolResultBlock result) {
        StringBuilder text = new StringBuilder();
        for (var block : result.getOutput()) {
            if (block instanceof TextBlock textBlock) {
                text.append(textBlock.getText());
            }
        }
        return text.toString();
    }

    private static final class RecordingHost implements SupervisorProjectCapabilityToolbox.Host {
        private String blocked;
        private final List<String> tracedTools = new ArrayList<>();
        private final List<Map<String, Object>> tracedInputs = new ArrayList<>();

        @Override public String blockedReason() { return blocked; }
        @Override public void throwIfCancelled() { }
        @Override public void phase(String stepId, String name, String state, String title, String detail) { }
        @Override public void trace(String toolName, Map<String, Object> input, Map<String, Object> outcome,
                                    boolean success, String code, long elapsedMs) {
            tracedTools.add(toolName);
            tracedInputs.add(input);
        }
    }
}
