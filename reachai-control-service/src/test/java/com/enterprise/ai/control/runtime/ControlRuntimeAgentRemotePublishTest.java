package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteBindingSnapshotAssembler;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ControlRuntimeAgentRemotePublishTest {

    @Test
    void publishesServerConfirmedEmptyRemoteCatalogWithoutUnrelatedRemoteManagePermission() {
        var fixture = new Fixture(List.of());
        when(fixture.assembler.assemble(fixture.request, fixture.config, "agent-1"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "remote manage required"));

        assertEquals(200, fixture.publish().getStatusCode().value());
        verifyNoInteractions(fixture.assembler);
        verify(fixture.runtime).publishAgentConfigVersion("agent-1", 31L, Map.of());
    }

    @Test
    void nonEmptyCatalogStillRequiresRemoteManagementAuthorization() {
        var fixture = new Fixture(List.of(Map.of("principalId", 11L)));
        when(fixture.assembler.assemble(fixture.request, fixture.config, "agent-1"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "remote manage required"));

        assertEquals(403, assertThrows(ResponseStatusException.class, fixture::publish)
                .getStatusCode().value());
        fixture.verifyNotPublished();
    }

    @Test
    void nonEmptyCatalogStillRejectsControlSnapshotDrift() {
        var fixture = new Fixture(List.of(Map.of("principalId", 11L)));
        when(fixture.assembler.assemble(fixture.request, fixture.config, "agent-1"))
                .thenReturn(Map.of("remoteAgents", List.of(Map.of("principalId", 12L))));

        assertEquals(409, assertThrows(ResponseStatusException.class, fixture::publish)
                .getStatusCode().value());
        fixture.verifyNotPublished();
    }

    @Test
    void malformedServerCatalogIsNotTreatedAsEmpty() {
        for (Object invalid : new Object[]{null, Map.of(), "[]"}) {
            var fixture = new Fixture(invalid);
            assertEquals(503, assertThrows(ResponseStatusException.class, fixture::publish)
                    .getStatusCode().value());
            verifyNoInteractions(fixture.assembler);
            fixture.verifyNotPublished();
        }
    }

    @Test
    void nonEmptyMatchingCatalogIsReattestedBeforePublishing() {
        var fixture = new Fixture(List.of(Map.of("principalId", 11L, "toolName", "remote_read")));
        when(fixture.assembler.assemble(fixture.request, fixture.config, "agent-1"))
                .thenReturn(fixture.config);

        assertEquals(200, fixture.publish().getStatusCode().value());
        verify(fixture.assembler).assemble(fixture.request, fixture.config, "agent-1");
        verify(fixture.runtime).publishAgentConfigVersion("agent-1", 31L, Map.of());
    }

    private static final class Fixture {
        final RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
        final A2aRemoteBindingSnapshotAssembler assembler = mock(A2aRemoteBindingSnapshotAssembler.class);
        final HttpServletRequest request = mock(HttpServletRequest.class);
        final Map<String, Object> config = new LinkedHashMap<>();
        final ControlRuntimePublicController controller = new ControlRuntimePublicController(runtime);

        Fixture(Object remoteAgents) {
            config.put("id", 31L);
            config.put("status", "DRAFT");
            config.put("remoteAgents", remoteAgents);
            controller.setA2aRemoteBindingSnapshotAssembler(assembler);
            when(runtime.listAgentConfigVersions("agent-1"))
                    .thenReturn(ResponseEntity.ok(List.of(config)));
            when(runtime.publishAgentConfigVersion("agent-1", 31L, Map.of()))
                    .thenReturn(ResponseEntity.ok(Map.of("id", 31L, "status", "ACTIVE")));
        }

        ResponseEntity<Object> publish() {
            return controller.publishAgentConfigVersion(request, "agent-1", 31L, Map.of());
        }

        void verifyNotPublished() {
            verify(runtime, never()).publishAgentConfigVersion(eq("agent-1"), eq(31L), anyMap());
        }
    }
}
