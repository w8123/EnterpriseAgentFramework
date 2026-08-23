package com.enterprise.ai.runtime.interaction;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.List;

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
        assertArrayEquals(new String[] {"/api/runtime/interactions/human-approvals"},
                list.getAnnotation(GetMapping.class).path());
    }

    @Test
    void delegatesHumanApprovalRoutesToRuntimeService() {
        RuntimeHumanApprovalService service = mock(RuntimeHumanApprovalService.class);
        RuntimeHumanApprovalController controller = new RuntimeHumanApprovalController(service);
        RuntimeHumanApprovalService.PendingHumanApprovalView approval =
                new RuntimeHumanApprovalService.PendingHumanApprovalView(
                        "approval-1", "trace-1", "session-1", "u-1", "agent-7", "node-1", "PENDING",
                        null, null, null, "Approve", "Confirm?", java.util.Map.of(), java.util.Map.of());
        when(service.listPendingHumanApprovals("agent-7", "u-1", 50)).thenReturn(List.of(approval));

        ResponseEntity<List<RuntimeHumanApprovalService.PendingHumanApprovalView>> listResponse =
                controller.humanApprovals("agent-7", "u-1", 50);

        assertEquals(HttpStatus.OK, listResponse.getStatusCode());
        assertEquals(List.of(approval), listResponse.getBody());
        verify(service).listPendingHumanApprovals("agent-7", "u-1", 50);
    }
}
