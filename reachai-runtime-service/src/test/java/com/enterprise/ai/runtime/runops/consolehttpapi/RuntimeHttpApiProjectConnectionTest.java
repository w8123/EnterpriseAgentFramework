package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialRuntime;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpEgressPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** D27: project default connection inherited by every API, with an optional per-API override. */
class RuntimeHttpApiProjectConnectionTest {
    private static final String REF = "http-api:fixture:dev:GET:/orders";
    private final ObjectMapper json = new ObjectMapper();
    private RuntimeHttpApiConnectionMapper overrides;
    private RuntimeHttpApiProjectConnectionMapper defaults;
    private RuntimeWorkflowCredentialService credentials;
    private RuntimeHttpApiConnectionService service;
    private HttpApiConsoleContracts.ExecutionContext owner;

    @BeforeEach
    void setUp() throws Exception {
        overrides = mock(RuntimeHttpApiConnectionMapper.class);
        defaults = mock(RuntimeHttpApiProjectConnectionMapper.class);
        credentials = mock(RuntimeWorkflowCredentialService.class);
        var capability = mock(RuntimeCapabilityCatalogClient.class);
        owner = new HttpApiConsoleContracts.ExecutionContext(1, 9L, REF, 7L, "fixture", "dev", "GET", "/orders",
                true, "ACCEPTED", null, "a".repeat(64), "a".repeat(64), "b".repeat(64), json.readTree("""
                {"identity":{"method":"GET","routeTemplate":"/orders","mappingConditions":{}},
                 "sideEffect":"READ_ONLY","parameters":[],"responses":[{"contentTypes":["application/json"],
                 "schema":{"type":"object"}}],"authentication":{"state":"NONE"}}
                """), true, null);
        when(capability.getHttpApiExecutionContext(9L, "fixture")).thenReturn(owner);
        // Origin syntax is still checked by the service; egress (DNS) is a separate, mocked policy here.
        service = new RuntimeHttpApiConnectionService(overrides, defaults, capability, credentials,
                mock(WorkflowHttpEgressPolicy.class));
    }

    @Test
    void apiWithoutOverrideInheritsTheProjectDefaultAndPinsItsEffectiveRevision() {
        when(defaults.selectOne(any())).thenReturn(projectDefault(3L, "http://default.test", "NONE", null));
        var view = service.read(command(null));
        assertEquals("CONFIGURED", view.status());
        assertEquals("PROJECT_DEFAULT", view.connectionSource());
        assertTrue(view.inherited());
        assertFalse(view.overrideActive());
        assertNull(view.overrideRevision());
        assertEquals("http://default.test", view.origin());
        assertEquals("http://default.test/orders", view.previewUrl());
        assertEquals(6L, view.revision());
        assertEquals(3L, view.projectDefault().revision());
        assertEquals("CONFIGURED", view.projectDefault().status());
        var snapshot = service.snapshot(owner);
        assertEquals(6L, snapshot.revision());
        assertTrue(snapshot.inherited());
    }

    @Test
    void activeOverrideWinsAndInactiveOverrideFallsBackWithoutReusingRevisions() {
        when(defaults.selectOne(any())).thenReturn(projectDefault(3L, "http://default.test", "NONE", null));
        var row = override(2L, "http://override.test", true);
        when(overrides.selectOne(any())).thenReturn(row);
        var active = service.read(command(null));
        assertEquals("OVERRIDE", active.connectionSource());
        assertFalse(active.inherited());
        assertEquals("http://override.test", active.origin());
        assertEquals(5L, active.revision());
        assertEquals(2L, active.overrideRevision());
        assertEquals("http://default.test", active.projectDefault().origin());

        row.setOverrideActive(false); row.setRevision(3L);
        var inherited = service.read(command(null));
        assertEquals("PROJECT_DEFAULT", inherited.connectionSource());
        assertEquals("http://default.test", inherited.origin());
        assertEquals(6L, inherited.revision());
        assertEquals(3L, inherited.overrideRevision());
        assertNotEquals(active.revision(), inherited.revision());
    }

    @Test
    void changingTheProjectDefaultChangesTheEffectiveRevisionOfEveryInheritingApi() {
        when(defaults.selectOne(any())).thenReturn(projectDefault(1L, "http://default.test", "NONE", null));
        Long before = service.snapshot(owner).revision();
        var current = projectDefault(1L, "http://default.test", "NONE", null);
        when(defaults.selectByScopeForUpdate(7L, "dev")).thenReturn(current);
        when(defaults.selectOne(any())).thenReturn(current);
        service.saveProjectDefault(command(save("http://moved.test", "NONE", null, 1L)), "actor-1");
        assertEquals(2L, current.getRevision());
        Long after = service.snapshot(owner).revision();
        assertNotEquals(before, after);
        assertEquals(RuntimeHttpApiConnectionService.effectiveRevision(2L, false), after);
        verify(defaults).updateById(current);
    }

    @Test
    void firstProjectDefaultSaveInsertsRevisionOneAndRejectsStaleOrMismatchedInput() {
        var inserted = ArgumentCaptor.forClass(RuntimeHttpApiProjectConnectionEntity.class);
        service.saveProjectDefault(command(save("http://default.test", "NONE", null, null)), "actor-1");
        verify(defaults).insert(inserted.capture());
        assertEquals(1L, inserted.getValue().getRevision());
        assertEquals(7L, inserted.getValue().getProjectId());
        assertEquals("fixture", inserted.getValue().getProjectCode());
        assertEquals("dev", inserted.getValue().getEnvironment());

        assertEquals("HTTP_API_PROJECT_CONNECTION_REVISION_CHANGED", assertThrows(RuntimeHttpApiConnectionService.Conflict.class,
                () -> service.saveProjectDefault(command(save("http://default.test", "NONE", null, 4L)), "actor-1")).code());
        assertThrows(IllegalArgumentException.class,
                () -> service.saveProjectDefault(command(save("http://default.test/path", "NONE", null, null)), "actor-1"));
        assertEquals("HTTP_API_AUTH_MISMATCH", assertThrows(RuntimeHttpApiConnectionService.Conflict.class,
                () -> service.saveProjectDefault(command(save("http://default.test", "NONE", "cred-1", null)), "actor-1")).code());
        when(credentials.resolveProjectCredential(eq("cred-1"), eq(7L), eq("fixture"))).thenReturn(Optional.of(
                new RuntimeWorkflowCredentialRuntime("cred-1", "Key", "BEARER", Map.of("token", "x"), "c".repeat(64))));
        assertEquals("HTTP_API_AUTH_MISMATCH", assertThrows(RuntimeHttpApiConnectionService.Conflict.class,
                () -> service.saveProjectDefault(command(save("http://default.test", "API_KEY_HEADER", "cred-1", null)), "actor-1")).code());
        when(credentials.resolveProjectCredential(eq("foreign"), eq(7L), eq("fixture"))).thenReturn(Optional.empty());
        assertEquals("HTTP_API_CREDENTIAL_UNAVAILABLE", assertThrows(RuntimeHttpApiConnectionService.Conflict.class,
                () -> service.saveProjectDefault(command(save("http://default.test", "BEARER", "foreign", null)), "actor-1")).code());
    }

    @Test
    void projectDefaultThatDoesNotFitTheContractIsBlockedWithAnOverrideHint() {
        when(credentials.resolveProjectCredential(eq("cred-1"), eq(7L), eq("fixture"))).thenReturn(Optional.of(
                new RuntimeWorkflowCredentialRuntime("cred-1", "Token", "BEARER", Map.of("token", "x"), "c".repeat(64))));
        when(defaults.selectOne(any())).thenReturn(projectDefault(1L, "http://default.test", "BEARER", "cred-1"));
        var view = service.read(command(null));
        assertEquals("BLOCKED", view.status());
        assertTrue(view.inherited());
        assertTrue(view.blockingReason().contains("项目默认连接不适用于当前 API"));
        assertEquals("CONFIGURED", view.projectDefault().status());
        assertTrue(view.configurationAllowed());
    }

    @Test
    void removingAnOverrideDeactivatesTheRowAndBumpsItsRevision() {
        var row = override(2L, "http://override.test", true);
        when(overrides.selectByRefForUpdate(REF)).thenReturn(row);
        when(overrides.selectOne(any())).thenReturn(row);
        when(defaults.selectOne(any())).thenReturn(projectDefault(1L, "http://default.test", "NONE", null));
        assertEquals("HTTP_API_CONNECTION_REVISION_CHANGED", assertThrows(RuntimeHttpApiConnectionService.Conflict.class,
                () -> service.removeOverride(command(save(null, null, null, 1L)), "actor-1")).code());
        var view = service.removeOverride(command(save(null, null, null, 2L)), "actor-1");
        assertEquals(Boolean.FALSE, row.getOverrideActive());
        assertEquals(3L, row.getRevision());
        assertEquals("PROJECT_DEFAULT", view.connectionSource());
        assertEquals(3L, view.overrideRevision());
        verify(overrides).updateById(row);
        // Already inherited: a second removal is a stale request, not a silent success.
        assertEquals("HTTP_API_CONNECTION_REVISION_CHANGED", assertThrows(RuntimeHttpApiConnectionService.Conflict.class,
                () -> service.removeOverride(command(save(null, null, null, 3L)), "actor-1")).code());

        // Re-creating an override continues the row's revision sequence instead of restarting at 1.
        when(overrides.selectByRefForUpdate(REF)).thenReturn(row);
        var recreated = service.save(command(save("http://again.test", "NONE", null, 3L)), "actor-1");
        assertEquals(4L, row.getRevision());
        assertEquals(Boolean.TRUE, row.getOverrideActive());
        assertEquals("OVERRIDE", recreated.connectionSource());
        assertEquals(9L, recreated.revision());
    }

    @Test
    void neitherOverrideNorDefaultIsUnconfigured() {
        var view = service.read(command(null));
        assertEquals("UNCONFIGURED", view.status());
        assertNull(view.revision());
        assertNull(view.connectionSource());
        assertEquals("UNCONFIGURED", view.projectDefault().status());
        assertNull(service.snapshot(owner));
    }

    private HttpApiConsoleContracts.ConnectionCommand command(HttpApiConsoleContracts.ConnectionSaveRequest save) {
        return new HttpApiConsoleContracts.ConnectionCommand(1, 9L, REF, 7L, "fixture", "dev", save);
    }

    private HttpApiConsoleContracts.ConnectionSaveRequest save(String origin, String mode, String ref, Long expected) {
        return new HttpApiConsoleContracts.ConnectionSaveRequest(origin, mode, ref, expected);
    }

    private RuntimeHttpApiProjectConnectionEntity projectDefault(Long revision, String origin, String mode, String ref) {
        var row = new RuntimeHttpApiProjectConnectionEntity();
        row.setId(11L); row.setProjectId(7L); row.setProjectCode("fixture"); row.setEnvironment("dev");
        row.setOrigin(origin); row.setAuthMode(mode); row.setCredentialRef(ref); row.setRevision(revision);
        return row;
    }

    private RuntimeHttpApiConnectionEntity override(Long revision, String origin, boolean active) {
        var row = new RuntimeHttpApiConnectionEntity();
        row.setId(21L); row.setQualifiedName(REF); row.setProjectId(7L); row.setProjectCode("fixture");
        row.setEnvironment("dev"); row.setOrigin(origin); row.setAuthMode("NONE");
        row.setOverrideActive(active); row.setRevision(revision);
        return row;
    }
}
