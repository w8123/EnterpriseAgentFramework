package com.enterprise.ai.runtime.interaction;

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

class RuntimeHumanApprovalControllerTest {

    @Test
    void keepsPublicAgentInteractionRoutesOnRuntimeService() throws Exception {
        Method list = RuntimeHumanApprovalController.class
                .getDeclaredMethod("humanApprovals", String.class, String.class, int.class);
        Method submit = RuntimeHumanApprovalController.class
                .getDeclaredMethod("submitHumanApproval", String.class, RuntimeHumanApprovalService.SubmitRequest.class);
        Method cancel = RuntimeHumanApprovalController.class
                .getDeclaredMethod("cancelHumanApproval", String.class, String.class);

        assertArrayEquals(new String[] {"/api/runtime/interactions/human-approvals"},
                list.getAnnotation(GetMapping.class).path());
        assertArrayEquals(new String[] {"/api/runtime/interactions/human-approvals/{interactionId}/submit"},
                submit.getAnnotation(PostMapping.class).path());
        assertArrayEquals(new String[] {"/api/runtime/interactions/human-approvals/{interactionId}"},
                cancel.getAnnotation(DeleteMapping.class).path());
    }

    @Test
    void delegatesHumanApprovalRoutesToRuntimeService() {
        RuntimeHumanApprovalService service = mock(RuntimeHumanApprovalService.class);
        RuntimeHumanApprovalController controller = new RuntimeHumanApprovalController(service);
        RuntimeHumanApprovalService.PendingHumanApprovalView approval =
                new RuntimeHumanApprovalService.PendingHumanApprovalView(
                        "approval-1", "trace-1", "session-1", "u-1", "agent-7", "node-1", "PENDING",
                        null, null, null, "Approve", "Confirm?", Map.of(), Map.of());
        RuntimeHumanApprovalService.SubmitRequest request =
                new RuntimeHumanApprovalService.SubmitRequest("approve", Map.of("confirm", true), "u-1", "session-1");
        RuntimeHumanApprovalService.AgentResultView result =
                new RuntimeHumanApprovalService.AgentResultView(true, "审批已通过", List.of(), Map.of(), Map.of(), null);
        when(service.listPendingHumanApprovals("agent-7", "u-1", 50)).thenReturn(List.of(approval));
        when(service.submitHumanApproval("approval-1", request)).thenReturn(result);
        when(service.cancelHumanApproval("approval-1", "u-1")).thenReturn(result);

        ResponseEntity<List<RuntimeHumanApprovalService.PendingHumanApprovalView>> listResponse =
                controller.humanApprovals("agent-7", "u-1", 50);
        ResponseEntity<RuntimeHumanApprovalService.AgentResultView> submitResponse =
                controller.submitHumanApproval("approval-1", request);
        ResponseEntity<RuntimeHumanApprovalService.AgentResultView> cancelResponse =
                controller.cancelHumanApproval("approval-1", "u-1");

        assertEquals(HttpStatus.OK, listResponse.getStatusCode());
        assertEquals(List.of(approval), listResponse.getBody());
        assertEquals(result, submitResponse.getBody());
        assertEquals(result, cancelResponse.getBody());
        verify(service).listPendingHumanApprovals("agent-7", "u-1", 50);
        verify(service).submitHumanApproval("approval-1", request);
        verify(service).cancelHumanApproval("approval-1", "u-1");
    }
}
