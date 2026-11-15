package com.enterprise.ai.runtime.execution.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RuntimeHttpEgressHostControllerTest {
    @Test
    void managementRoutesMatchTheAdminFacadeWhileInternalHostReadsStaySeparate() throws Exception {
        var service = mock(RuntimeHttpEgressHostService.class);
        var policy = mock(WorkflowHttpEgressPolicy.class);
        when(service.list()).thenReturn(List.of());
        when(policy.environmentAllowlist()).thenReturn(Set.of("configured.example"));
        when(service.hosts()).thenReturn(Set.of("approved.example"));
        var added = new RuntimeHttpEgressHostEntity();
        added.setId(7L);
        added.setHost("approved.example");
        when(service.add("approved.example", "ERP", "admin")).thenReturn(added);
        var mvc = MockMvcBuilders.standaloneSetup(new RuntimeHttpEgressHostController(service, policy)).build();

        mvc.perform(get("/api/platform/http-egress-hosts").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.hosts").isArray())
                .andExpect(jsonPath("$.environmentHosts[0]").value("configured.example"));
        mvc.perform(post("/api/platform/http-egress-hosts").accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"host":"approved.example","note":"ERP","createdBy":"admin"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("approved.example"));
        verify(service).add("approved.example", "ERP", "admin");
        mvc.perform(delete("/api/platform/http-egress-hosts/7").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.removed").value(true));
        verify(service).remove(7L);
        mvc.perform(get("/internal/runtime/http-egress-hosts").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("approved.example"))
                .andExpect(jsonPath("$[1]").value("configured.example"));
    }
}
