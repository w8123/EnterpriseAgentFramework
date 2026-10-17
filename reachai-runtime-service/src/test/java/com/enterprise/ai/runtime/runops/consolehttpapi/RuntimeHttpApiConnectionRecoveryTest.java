package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialRuntime;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpEgressPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeHttpApiConnectionRecoveryTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void deniedSavedOriginCanBeCorrectedWithoutGrantingAnInvocation() throws Exception {
        var fixture = fixture("ready", true);
        when(fixture.egress.validateUri("http://old.invalid/")).thenThrow(new IllegalArgumentException("denied"));
        var blocked = fixture.service.read(command(null));
        assertEquals("BLOCKED", blocked.status());
        assertTrue(json.valueToTree(blocked).path("configurationAllowed").asBoolean(false));
        assertEquals(1L, blocked.overrideRevision()); assertEquals(3L, blocked.revision());
        var repaired = fixture.service.save(command(new HttpApiConsoleContracts.ConnectionSaveRequest(
                "http://127.0.0.1:8080", "NONE", null, 1L)), "actor");
        assertEquals("CONFIGURED", repaired.status()); assertEquals(2L, repaired.overrideRevision());
        verify(fixture.rows).updateById(fixture.saved);
    }

    @Test void disabledSavedCredentialCanBeReplacedByAnActiveProjectCredential() throws Exception {
        var fixture = fixture("ready", true);
        fixture.saved.setAuthMode("BEARER"); fixture.saved.setCredentialRef("disabled");
        when(fixture.credentials.resolveProjectCredential("disabled", 7L, "fixture")).thenReturn(Optional.empty());
        when(fixture.credentials.resolveProjectCredential("active", 7L, "fixture")).thenReturn(Optional.of(
                new RuntimeWorkflowCredentialRuntime("active", "Synthetic", "BEARER", Map.of("token", "fixture-only"), "c".repeat(64))));
        var blocked = fixture.service.read(command(null));
        assertEquals("BLOCKED", blocked.status());
        assertTrue(json.valueToTree(blocked).path("configurationAllowed").asBoolean(false));
        var repaired = fixture.service.save(command(new HttpApiConsoleContracts.ConnectionSaveRequest(
                "http://new.invalid", "BEARER", "active", 1L)), "actor");
        assertEquals("CONFIGURED", repaired.status()); assertEquals("active", repaired.credentialRef());
        assertEquals(2L, repaired.overrideRevision()); assertEquals(5L, repaired.revision());
    }

    @ParameterizedTest @ValueSource(strings={"unaccepted", "unconfirmed", "drift", "unsupported", "foreign-scope"})
    void sourceContractAndScopeBlockersCannotBecomeEditable(String state) throws Exception {
        var fixture = fixture(state, true);
        assertFalse(json.valueToTree(fixture.service.read(command(null))).path("configurationAllowed").asBoolean(false));
        assertThrows(RuntimeHttpApiConnectionService.Conflict.class,
                () -> fixture.service.save(command(new HttpApiConsoleContracts.ConnectionSaveRequest("http://new.invalid", "NONE", null, 1L)), "actor"));
        verify(fixture.rows, never()).updateById(any());
    }

    @ParameterizedTest @ValueSource(strings={"ready", "unsupported", "unconfirmed"})
    void newConnectionsExposeOnlyOwnerBackedConfigurationPermission(String state) throws Exception {
        var fixture = fixture(state, false);
        assertEquals("ready".equals(state), json.valueToTree(fixture.service.read(command(null)))
                .path("configurationAllowed").asBoolean(false));
    }

    private Fixture fixture(String state, boolean saved) throws Exception {
        var rows = mock(RuntimeHttpApiConnectionMapper.class);
        var capability = mock(RuntimeCapabilityCatalogClient.class);
        var credentials = mock(RuntimeWorkflowCredentialService.class);
        var egress = mock(WorkflowHttpEgressPolicy.class);
        var contract = json.readTree("""
                {"identity":{"method":"GET","routeTemplate":"/fixture","mappingConditions":{}},
                 "sideEffect":"READ_ONLY","parameters":[],"responses":[{"contentTypes":["application/json"],
                 "schema":{"type":"object"}}],"authentication":{"state":"UNKNOWN"}}
                """);
        var owner = new HttpApiConsoleContracts.ExecutionContext(1, 9L, "http-api:fixture", 7L, "fixture", "dev",
                "GET", "/fixture", !"unconfirmed".equals(state), "unaccepted".equals(state) ? "DISCOVERED" : "ACCEPTED", null,
                "drift".equals(state) ? "d".repeat(64) : "a".repeat(64), "a".repeat(64), "b".repeat(64), contract,
                !"unsupported".equals(state), "unsupported".equals(state) ? "unsupported multipart" : null);
        when(capability.getHttpApiExecutionContext(9L, "fixture")).thenReturn(owner);
        var entity = new RuntimeHttpApiConnectionEntity(); entity.setId(1L); entity.setQualifiedName("http-api:fixture");
        entity.setProjectId("foreign-scope".equals(state) ? 8L : 7L); entity.setProjectCode("fixture"); entity.setEnvironment("dev");
        entity.setOrigin("http://old.invalid"); entity.setAuthMode("NONE"); entity.setRevision(1L);
        if (saved) {
            when(rows.selectOne(any())).thenReturn(entity);
            when(rows.selectByRefForUpdate("http-api:fixture")).thenReturn(entity);
        }
        return new Fixture(new RuntimeHttpApiConnectionService(rows, mock(RuntimeHttpApiProjectConnectionMapper.class), capability, credentials, egress), rows, credentials, egress, entity);
    }

    private HttpApiConsoleContracts.ConnectionCommand command(HttpApiConsoleContracts.ConnectionSaveRequest save) {
        return new HttpApiConsoleContracts.ConnectionCommand(1, 9L, "http-api:fixture", 7L, "fixture", "dev", save);
    }
    private record Fixture(RuntimeHttpApiConnectionService service, RuntimeHttpApiConnectionMapper rows,
                           RuntimeWorkflowCredentialService credentials, WorkflowHttpEgressPolicy egress,
                           RuntimeHttpApiConnectionEntity saved) { }
}
