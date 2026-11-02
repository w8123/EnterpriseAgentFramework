package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import com.enterprise.ai.control.memory.PersonalMemoryGroupService;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmployeePortalMemoryControllerTest {

    private final EmployeePortalSessionService sessions = mock(EmployeePortalSessionService.class);
    private final PersonalMemoryService memoryService = mock(PersonalMemoryService.class);
    private final EmployeePortalMemoryController controller = new EmployeePortalMemoryController(
            sessions, memoryService, mock(PersonalMemoryGroupService.class));

    @Test
    void ownerIsThePortalEmployeeSameAsEmbeddedChat() {
        when(sessions.resolve("tok")).thenReturn(Optional.of(new EmployeeOwner(
                "tenant-a", 9L, "yudao", "yudao-erp", " erp-42 ", "张三", List.of("erp_staff"))));
        when(memoryService.list(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PersonalMemoryService.MemoryPage(List.of(), 0, 50, 0));

        var response = controller.list("Bearer tok", null, null, null, null, 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(memoryService).list(eq(PersonalMemoryPrincipal.business("tenant-a", "erp-42", "yudao-erp")),
                any(), any(), any(), any(), eq(50), eq(0));
    }

    @Test
    void expiredOrMissingSessionIsUnauthorizedAndTouchesNoMemory() {
        when(sessions.resolve(any())).thenReturn(Optional.empty());

        assertEquals(HttpStatus.UNAUTHORIZED, controller.list(null, null, null, null, null, 50, 0).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.forget("Bearer old", 1L).getStatusCode());
        verifyNoInteractions(memoryService);
    }
}
