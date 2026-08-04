package com.enterprise.ai.runtime.api;

import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialRequest;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialView;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowCredentialPublicControllerTest {

    @Test
    void exposesOnlyCanonicalCredentialRoutes() throws Exception {
        Method list = RuntimeWorkflowCredentialPublicController.class.getDeclaredMethod(
                "list", Long.class, String.class);
        Method create = RuntimeWorkflowCredentialPublicController.class.getDeclaredMethod(
                "create", RuntimeWorkflowCredentialRequest.class);
        Method update = RuntimeWorkflowCredentialPublicController.class.getDeclaredMethod(
                "update", Long.class, RuntimeWorkflowCredentialRequest.class);
        Method delete = RuntimeWorkflowCredentialPublicController.class.getDeclaredMethod("delete", Long.class);

        assertArrayEquals(new String[]{"/api/workflows/credentials"}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/credentials"}, create.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/credentials/{id}"}, update.getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[]{"/api/workflows/credentials/{id}"}, delete.getAnnotation(DeleteMapping.class).value());
    }

    @Test
    void delegatesCredentialOperations() {
        RuntimeWorkflowCredentialService service = mock(RuntimeWorkflowCredentialService.class);
        RuntimeWorkflowCredentialPublicController controller = new RuntimeWorkflowCredentialPublicController(service);
        RuntimeWorkflowCredentialRequest request = new RuntimeWorkflowCredentialRequest(
                "cred_orders", "Orders API", "BEARER", 7L, "orders", "PROJECT", "ACTIVE",
                Map.of("token", "secret-token-123"));
        RuntimeWorkflowCredentialView view = new RuntimeWorkflowCredentialView(
                1L, "cred_orders", "Orders API", "BEARER", 7L, "orders", "PROJECT", "ACTIVE",
                Map.of("token", "se****23"), null, null);
        when(service.list(7L, "orders")).thenReturn(List.of(view));
        when(service.create(request)).thenReturn(view);
        when(service.update(1L, request)).thenReturn(view);

        assertEquals(List.of(view), controller.list(7L, "orders"));
        assertEquals(view, controller.create(request));
        assertEquals(view, controller.update(1L, request));
        assertEquals(HttpStatus.NO_CONTENT, controller.delete(1L).getStatusCode());
        verify(service).list(7L, "orders");
        verify(service).create(request);
        verify(service).update(1L, request);
        verify(service).delete(1L);
    }
}
