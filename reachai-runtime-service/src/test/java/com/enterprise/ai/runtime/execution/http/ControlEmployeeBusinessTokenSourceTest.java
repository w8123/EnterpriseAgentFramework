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
                new EmployeeBusinessTokenResolution("REQUIRED", List.of("https://erp.example:443"), "secret"),
                new EmployeeBusinessTokenResolution("REQUIRED", List.of("https://erp.example:443"), null));

        var resolution = source.resolve(employee);
        assertEquals("REQUIRED", resolution.mode());
        assertEquals(List.of("https://erp.example:443"), resolution.origins());
        assertEquals("secret", resolution.token());
        assertEquals(resolution, source.resolve(employee));
        source.expired(employee);
        verify(control).employeeBusinessTokenExpired(subject);
        assertNull(source.resolve(employee).token());
        verify(control, times(3)).resolveEmployeeBusinessToken(subject);
    }

    @Test
    void everyRuntimeObservesPolicyChangesOnItsNextResolution() {
        var secondRuntime = new ControlEmployeeBusinessTokenSource(control);
        var current = new java.util.concurrent.atomic.AtomicReference<>(
                new EmployeeBusinessTokenResolution("OFF", List.of(), null));
        when(control.resolveEmployeeBusinessToken(subject)).thenAnswer(call -> current.get());
        assertEquals("OFF", source.resolve(employee).mode());
        assertEquals("OFF", secondRuntime.resolve(employee).mode());

        current.set(new EmployeeBusinessTokenResolution("REQUIRED", List.of("https://erp.example:443"), null));
        assertEquals("REQUIRED", source.resolve(employee).mode());
        assertEquals("REQUIRED", secondRuntime.resolve(employee).mode());

        current.set(new EmployeeBusinessTokenResolution("PREFERRED", List.of("https://erp.example:443"), "new-token"));
        assertEquals("new-token", source.resolve(employee).token());
        assertEquals("new-token", secondRuntime.resolve(employee).token());

        current.set(new EmployeeBusinessTokenResolution("OFF", List.of(), null));
        assertEquals("OFF", source.resolve(employee).mode());
        assertEquals("OFF", secondRuntime.resolve(employee).mode());
    }

    @Test
    void previouslyAllowedSharedCredentialsCannotBeReusedWhenControlIsUnavailable() {
        when(control.resolveEmployeeBusinessToken(subject))
                .thenReturn(new EmployeeBusinessTokenResolution("OFF", List.of(), null))
                .thenThrow(new IllegalStateException("unavailable"));
        assertEquals("OFF", source.resolve(employee).mode());
        assertThrows(WorkflowHttpClient.WorkflowHttpExecutionException.class, () -> source.resolve(employee));
    }

    @Test
    void unavailableCredentialResolutionNeverFallsBackToTheSharedCredential() {
        when(control.resolveEmployeeBusinessToken(subject)).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(WorkflowHttpClient.WorkflowHttpExecutionException.class, () -> source.resolve(employee));
    }

    @Test
    void aMissingControlAnswerCannotBeTreatedAsPermissionToUseSharedCredentials() {
        when(control.resolveEmployeeBusinessToken(subject)).thenReturn(null);
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
