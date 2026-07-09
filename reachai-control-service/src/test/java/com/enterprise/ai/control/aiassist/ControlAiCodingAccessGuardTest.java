package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ControlAiCodingAccessGuardTest {

    @Test
    void rejectsMissingProjectKeyBeforeProjectLookup() {
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingAccessGuard guard = new ControlAiCodingAccessGuard(capabilityClient, runtimeClient);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> guard.requireProjectAccess(7L, null));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        verifyNoInteractions(capabilityClient, runtimeClient);
    }

    @Test
    void rejectsDisabledProjectAccess() {
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingAccessGuard guard = new ControlAiCodingAccessGuard(capabilityClient, runtimeClient);
        when(capabilityClient.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "aiCodingAccess", Map.of("enabled", false, "accessKey", "rac_secret")
        ));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> guard.requireProjectAccess(7L, "rac_secret"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(capabilityClient).getOnboardingProjectById(7L);
    }

    @Test
    void rejectsInvalidProjectKey() {
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingAccessGuard guard = new ControlAiCodingAccessGuard(capabilityClient, runtimeClient);
        when(capabilityClient.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "rac_secret")
        ));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> guard.requireProjectAccess(7L, "wrong"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @Test
    void acceptsValidProjectKey() {
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingAccessGuard guard = new ControlAiCodingAccessGuard(capabilityClient, runtimeClient);
        when(capabilityClient.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "rac_secret")
        ));

        assertDoesNotThrow(() -> guard.requireProjectAccess(7L, " rac_secret "));
    }

    @Test
    void resolvesWorkflowProjectBeforeCheckingWorkflowAccess() {
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingAccessGuard guard = new ControlAiCodingAccessGuard(capabilityClient, runtimeClient);
        when(runtimeClient.workflowAiCodingContext("wf-1")).thenReturn(ResponseEntity.ok(Map.of(
                "workflow", Map.of("id", "wf-1", "projectId", 7L)
        )));
        when(capabilityClient.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "rac_secret")
        ));

        assertDoesNotThrow(() -> guard.requireWorkflowAccess("wf-1", "rac_secret"));

        verify(runtimeClient).workflowAiCodingContext("wf-1");
        verify(capabilityClient).getOnboardingProjectById(7L);
    }

    @Test
    void rejectsMissingWorkflowKeyBeforeRuntimeLookup() {
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingAccessGuard guard = new ControlAiCodingAccessGuard(capabilityClient, runtimeClient);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> guard.requireWorkflowAccess("wf-1", ""));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        verifyNoInteractions(capabilityClient, runtimeClient);
    }

    @Test
    void checksCreateWorkflowProjectFromRequestBody() {
        CapabilityProjectOnboardingClient capabilityClient = mock(CapabilityProjectOnboardingClient.class);
        RuntimeProxyClient runtimeClient = mock(RuntimeProxyClient.class);
        ControlAiCodingAccessGuard guard = new ControlAiCodingAccessGuard(capabilityClient, runtimeClient);
        when(capabilityClient.getOnboardingProjectById(7L)).thenReturn(Map.of(
                "id", 7L,
                "aiCodingAccess", Map.of("enabled", true, "accessKey", "rac_secret")
        ));

        assertDoesNotThrow(() -> guard.requireWorkflowCreateAccess(Map.of("projectId", 7), "rac_secret"));

        verify(capabilityClient).getOnboardingProjectById(7L);
    }
}
