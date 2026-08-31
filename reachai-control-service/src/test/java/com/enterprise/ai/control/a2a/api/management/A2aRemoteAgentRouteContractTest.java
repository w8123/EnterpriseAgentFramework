package com.enterprise.ai.control.a2a.api.management;

import com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteAgentApplicationService;
import com.enterprise.ai.control.a2a.application.remoteagent.A2aRemoteAgentContracts.DiscoveryResult;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class A2aRemoteAgentRouteContractTest {

    private final A2aRemoteAgentApplicationService service =
            mock(A2aRemoteAgentApplicationService.class);
    private final A2aHubManagementAccess access = mock(A2aHubManagementAccess.class);
    private final PlatformAuthAuditService audit = mock(PlatformAuthAuditService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PlatformAuthenticatedSession session = mock(PlatformAuthenticatedSession.class);
        when(access.require(any(), eq(A2aHubManagementAccess.MANAGE_REMOTE_AGENTS)))
                .thenReturn(session);
        when(access.actor(session)).thenReturn("route-test");

        DiscoveryResult result = mock(DiscoveryResult.class, RETURNS_DEEP_STUBS);
        when(result.detail().remoteAgent().id()).thenReturn(1L);
        when(result.detail().remoteAgent().remoteAgentKey()).thenReturn("remote-reviewer");
        when(result.detail().remoteAgent().status()).thenReturn("REVIEW_REQUIRED");
        when(result.outcome()).thenReturn("REVIEW_REQUIRED");
        when(service.discover(any(), eq("route-test"))).thenReturn(result);

        mvc = standaloneSetup(new A2aRemoteAgentController(service, access, audit)).build();
    }

    @Test
    void discoverUsesTheColonSuffixedProductContractWithoutAnInsertedSlash() throws Exception {
        mvc.perform(post("/api/a2a-hub/remote-agents:discover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "remoteAgentKey":"remote-reviewer",
                                  "displayName":"Remote Reviewer",
                                  "agentCardUrl":"https://agent.example/agent-card.json"
                                }
                                """))
                .andExpect(status().isCreated());

        verify(service).discover(any(), eq("route-test"));
    }
}
