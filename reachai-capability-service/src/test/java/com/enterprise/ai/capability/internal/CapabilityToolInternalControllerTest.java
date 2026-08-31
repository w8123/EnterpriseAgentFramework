package com.enterprise.ai.capability.internal;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class CapabilityToolInternalControllerTest {

    @Test
    void exposesInternalToolExecuteRoute() throws Exception {
        Method execute = CapabilityToolInternalController.class.getMethod("executeTool", String.class, Map.class);
        PostMapping mapping = execute.getAnnotation(PostMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/tools/{qualifiedName}/execute"}, mapping.value());
    }

    @Test
    void exposesCanonicalInvocationRoute() throws Exception {
        Method invoke = CapabilityToolInternalController.class.getMethod("invoke", Map.class);
        PostMapping mapping = invoke.getAnnotation(PostMapping.class);
        assertArrayEquals(new String[] {"/internal/capability/invocations"}, mapping.value());
    }

    @Test
    void delegatesToolExecutionToService() {
        CapabilityToolLookupService lookupService = mock(CapabilityToolLookupService.class);
        CapabilityToolExecutionService executionService = mock(CapabilityToolExecutionService.class);
        CapabilityInvocationApplicationService invocationService =
                mock(CapabilityInvocationApplicationService.class);
        CapabilityToolInternalController controller = new CapabilityToolInternalController(
                lookupService, executionService, invocationService);
        Map<String, Object> request = Map.of("input", Map.of("orderNo", "A001"));
        Map<String, Object> expected = Map.of("success", true, "data", Map.of("orderStatus", "PAID"));
        when(executionService.execute("orders:queryOrder", request)).thenReturn(expected);

        ResponseEntity<Map<String, Object>> response = controller.executeTool("orders:queryOrder", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(executionService).execute("orders:queryOrder", request);
    }

    @Test
    void typedInvocationUsesVersionedServiceBoundary() {
        CapabilityToolLookupService lookupService = mock(CapabilityToolLookupService.class);
        CapabilityToolExecutionService executionService = mock(CapabilityToolExecutionService.class);
        CapabilityInvocationApplicationService invocationService =
                mock(CapabilityInvocationApplicationService.class);
        CapabilityToolInternalController controller = new CapabilityToolInternalController(
                lookupService, executionService, invocationService);
        CapabilityInvocationResponse typed = new CapabilityInvocationResponse(
                1, "inv-1", "orders:queryOrder", "queryOrder", "查询订单",
                CapabilityInvocationStatus.SUCCEEDED, true, Map.of("status", "PAID"),
                null, null, CapabilityInvocationFailureCategory.NONE, false, 3L, 1,
                null, Map.of("statusCode", 200));
        when(invocationService.invoke(any(CapabilityInvocationRequest.class))).thenReturn(typed);
        Map<String, Object> request = Map.of(
                "contractVersion", 1,
                "invocationId", "inv-1",
                "qualifiedName", "orders:queryOrder",
                "input", Map.of("orderNo", "A001"),
                "context", Map.of());

        ResponseEntity<Map<String, Object>> response = controller.executeTool("orders:queryOrder", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SUCCEEDED", response.getBody().get("status"));
        assertEquals("inv-1", response.getBody().get("invocationId"));
        verify(invocationService).invoke(any(CapabilityInvocationRequest.class));
    }

    @Test
    void canonicalInvocationUsesStrictWireContract() {
        CapabilityInvocationApplicationService invocationService =
                mock(CapabilityInvocationApplicationService.class);
        CapabilityToolInternalController controller = new CapabilityToolInternalController(
                mock(CapabilityToolLookupService.class),
                mock(CapabilityToolExecutionService.class),
                invocationService);
        CapabilityInvocationResponse typed = new CapabilityInvocationResponse(
                1, "inv-2", "system.echo", "echo", "Echo",
                CapabilityInvocationStatus.SUCCEEDED, true, Map.of("value", "ok"),
                null, null, CapabilityInvocationFailureCategory.NONE, false,
                1L, 1, null, Map.of("transport", "BUILTIN"));
        when(invocationService.invoke(any(CapabilityInvocationRequest.class))).thenReturn(typed);

        ResponseEntity<?> accepted = controller.invoke(Map.of(
                "contractVersion", 1,
                "invocationId", "inv-2",
                "qualifiedName", "system.echo",
                "input", Map.of("value", "ok"),
                "context", Map.of()));
        ResponseEntity<?> rejected = controller.invoke(Map.of(
                "contractVersion", 2,
                "invocationId", "inv-2",
                "qualifiedName", "system.echo"));

        assertEquals(HttpStatus.OK, accepted.getStatusCode());
        assertEquals(typed, accepted.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        verify(invocationService).invoke(any(CapabilityInvocationRequest.class));
    }
}
