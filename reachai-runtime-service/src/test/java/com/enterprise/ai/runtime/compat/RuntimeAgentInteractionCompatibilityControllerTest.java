package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.interaction.RuntimeHumanApprovalService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeAgentInteractionCompatibilityControllerTest {

    @Test
    void keepsPublicAgentInteractionRoutesOnRuntimeService() throws Exception {
        Method list = RuntimeAgentInteractionCompatibilityController.class
                .getDeclaredMethod("humanApprovals", Long.class, String.class, int.class);
        Method submit = RuntimeAgentInteractionCompatibilityController.class
                .getDeclaredMethod("submitHumanApproval", String.class, RuntimeHumanApprovalService.SubmitRequest.class);
        Method cancel = RuntimeAgentInteractionCompatibilityController.class
                .getDeclaredMethod("cancelHumanApproval", String.class, String.class);

        assertArrayEquals(new String[] {"/api/agent/interactions/human-approvals",
                        "/api/runtime/interactions/human-approvals"},
                list.getAnnotation(GetMapping.class).path());
        assertArrayEquals(new String[] {"/api/agent/interactions/human-approvals/{interactionId}/submit",
                        "/api/runtime/interactions/human-approvals/{interactionId}/submit"},
                submit.getAnnotation(PostMapping.class).path());
        assertArrayEquals(new String[] {"/api/agent/interactions/human-approvals/{interactionId}",
                        "/api/runtime/interactions/human-approvals/{interactionId}"},
                cancel.getAnnotation(DeleteMapping.class).path());
    }

    @Test
    void delegatesHumanApprovalRoutesToRuntimeService() {
        RuntimeHumanApprovalService service = mock(RuntimeHumanApprovalService.class);
        RuntimeAgentInteractionCompatibilityController controller =
                new RuntimeAgentInteractionCompatibilityController(service);
        RuntimeHumanApprovalService.PendingHumanApprovalView approval =
                new RuntimeHumanApprovalService.PendingHumanApprovalView(
                        "approval-1", "trace-1", "session-1", "u-1", 7L, "node-1", "PENDING",
                        null, null, null, "Approve", "Confirm?", Map.of(), Map.of());
        RuntimeHumanApprovalService.SubmitRequest request =
                new RuntimeHumanApprovalService.SubmitRequest("approve", Map.of("confirm", true), "u-1", "session-1");
        RuntimeHumanApprovalService.AgentResultView result =
                new RuntimeHumanApprovalService.AgentResultView(true, "审批已通过", List.of(), Map.of(), Map.of(), null);
        when(service.listPendingHumanApprovals(7L, "u-1", 50)).thenReturn(List.of(approval));
        when(service.submitHumanApproval("approval-1", request)).thenReturn(result);
        when(service.cancelHumanApproval("approval-1", "u-1")).thenReturn(result);

        ResponseEntity<List<RuntimeHumanApprovalService.PendingHumanApprovalView>> listResponse =
                controller.humanApprovals(7L, "u-1", 50);
        ResponseEntity<RuntimeHumanApprovalService.AgentResultView> submitResponse =
                controller.submitHumanApproval("approval-1", request);
        ResponseEntity<RuntimeHumanApprovalService.AgentResultView> cancelResponse =
                controller.cancelHumanApproval("approval-1", "u-1");

        assertEquals(HttpStatus.OK, listResponse.getStatusCode());
        assertEquals(List.of(approval), listResponse.getBody());
        assertEquals(result, submitResponse.getBody());
        assertEquals(result, cancelResponse.getBody());
        verify(service).listPendingHumanApprovals(7L, "u-1", 50);
        verify(service).submitHumanApproval("approval-1", request);
        verify(service).cancelHumanApproval("approval-1", "u-1");
    }
}
