package com.enterprise.ai.control.skillmarket;

import com.enterprise.ai.control.identity.PlatformPrincipal;

import com.enterprise.ai.control.agentskill.AgentSkillAccessPolicy;
import com.enterprise.ai.control.config.web.AgentSkillExceptionHandler;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SkillMarketControllerTest {

    private SkillMarketService marketService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        marketService = mock(SkillMarketService.class);
        SkillMarketController controller = new SkillMarketController(
                marketService, new AgentSkillAccessPolicy(), mock(PlatformAuthAuditService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AgentSkillExceptionHandler())
                .build();
    }

    @Test
    void searchRequiresLivePlatformSession() throws Exception {
        mockMvc.perform(get("/api/skill-market/search").param("query", "react"))
                .andExpect(status().isUnauthorized());

        verify(marketService, never()).search(any(), any(), any(), any(), anyInt());
    }

    @Test
    void readerCanSearchWithoutReceivingImportAuthority() throws Exception {
        when(marketService.search(isNull(), anyString(), isNull(), isNull(), anyInt()))
                .thenReturn(new SearchResult("SKILLS_SH", "LEGACY_PUBLIC_SEARCH", "react",
                        "all-time", 0, false, "metadata only", List.of()));

        mockMvc.perform(get("/api/skill-market/search")
                        .param("query", "react")
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE,
                                session("skill:read", "GLOBAL", "*")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("SKILLS_SH"))
                .andExpect(jsonPath("$.authenticatedUpstream").value(false));
    }

    @Test
    void probeRejectsReadOnlyRoleBeforeAnyRemoteFetch() throws Exception {
        mockMvc.perform(post("/api/skill-market/probes")
                        .contentType("application/json")
                        .content("{\"sourceUrl\":\"https://github.com/anthropics/skills\"}")
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE,
                                session("skill:read", "GLOBAL", "*")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SKILL_ACCESS_DENIED"));

        verify(marketService, never()).probe(any());
    }

    @Test
    void projectImportRejectsAuthorityForAnotherProjectBeforeMutation() throws Exception {
        String request = """
                {"sourceUrl":"https://github.com/anthropics/skills",
                 "commitSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "sourceRoot":"repo/skills/demo",
                 "expectedSelectedSourceSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                 "visibility":"PROJECT","projectCode":"hr-core"}
                """;

        mockMvc.perform(post("/api/skill-market/imports")
                        .contentType("application/json")
                        .content(request)
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE,
                                session("skill:import", "PROJECT", "finance-core")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SKILL_ACCESS_DENIED"));

        verify(marketService, never()).importFromMarket(any(), any(), anyString());
    }

    private PlatformAuthenticatedSession session(String permission,
                                                 String scopeType,
                                                 String scopeValue) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        user.setUsername("user-7");
        return new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user),
                "session",
                LocalDateTime.now().plusHours(1),
                List.of(),
                List.of(permission),
                List.of(new PlatformPermissionGrant(permission, scopeType, scopeValue)));
    }
}
