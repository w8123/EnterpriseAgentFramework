package com.enterprise.ai.runtime.execution.http;

import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.EmployeeBusinessTokenResolution;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.EmployeeBusinessTokenSubject;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlEmployeeBusinessTokenSourceTest {
    private final RuntimeControlCatalogClient control = mock(RuntimeControlCatalogClient.class);
    private final ControlEmployeeBusinessTokenSource source = new ControlEmployeeBusinessTokenSource(control);
    private final WorkflowExecutionIdentity employee = WorkflowExecutionIdentity.fromEmbedSession("tenant", 7L, "erp", "u1");
    private final EmployeeBusinessTokenSubject subject = new EmployeeBusinessTokenSubject("tenant", "erp", "u1");

    @Test
    void adaptsTheClientContractWithoutLosingTokenScopeAndInvalidatesItAfterRejection() {
        when(control.resolveEmployeeBusinessToken(subject)).thenReturn(
                new EmployeeBusinessTokenResolution("REQUIRED", List.of("https://erp.example:443"), "secret"),
                new EmployeeBusinessTokenResolution("REQUIRED", List.of("https://erp.example:443"), null));

        var resolution = source.resolve(employee);
        assertEquals("REQUIRED", resolution.mode());
        assertEquals(List.of("https://erp.example:443"), resolution.origins());
        assertEquals("secret", resolution.token());
        assertSame(resolution, source.resolve(employee));
        source.expired(employee);
        verify(control).employeeBusinessTokenExpired(subject);
        assertNull(source.resolve(employee).token());
        verify(control, times(2)).resolveEmployeeBusinessToken(subject);
    }

    @Test
    void unavailableCredentialResolutionNeverFallsBackToTheSharedCredential() {
        when(control.resolveEmployeeBusinessToken(subject)).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(WorkflowHttpClient.WorkflowHttpExecutionException.class, () -> source.resolve(employee));
    }

    @Test
    void clientAndExecutionContractsBothRedactTokens() {
        var contract = new EmployeeBusinessTokenResolution("REQUIRED", List.of(), "secret-value");
        assertFalse(contract.toString().contains("secret-value"));
        assertFalse(new EmployeeBusinessTokenSource.Resolution(contract.mode(), contract.origins(), contract.token())
                .toString().contains("secret-value"));
    }
}
