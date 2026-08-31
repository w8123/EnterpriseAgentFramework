package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.agentskill.AgentSkillCatalogService;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillDetail;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillSummary;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.VersionView;
import com.enterprise.ai.control.agentskill.AgentSkillExceptionHandler;
import com.enterprise.ai.control.internalauth.ControlInternalServiceAuthVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalAgentSkillCatalogControllerTest {

    private AgentSkillCatalogService catalogService;
    private ControlInternalServiceAuthVerifier authVerifier;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        catalogService = mock(AgentSkillCatalogService.class);
        authVerifier = mock(ControlInternalServiceAuthVerifier.class);
        doNothing().when(authVerifier).requireRuntime(any(), any(byte[].class));
        InternalAgentSkillCatalogController controller = new InternalAgentSkillCatalogController(
                catalogService, authVerifier, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AgentSkillExceptionHandler())
                .build();
    }

    @Test
    void resolveExecutionAuthenticatesExactBodyAndSeparatesExecutableStatuses() throws Exception {
        when(catalogService.version(11L, 21L)).thenReturn(version(21L, 11L, "PUBLISHED", "a"));
        when(catalogService.version(12L, 22L)).thenReturn(version(22L, 12L, "DEPRECATED", "b"));
        when(catalogService.version(13L, 23L)).thenReturn(version(23L, 13L, "REVOKED", "c"));
        String body = "["
                + "{\"skillId\":11,\"skillVersionId\":21,\"sourceSha256\":\"" + "a".repeat(64) + "\"},"
                + "{\"skillId\":12,\"skillVersionId\":22,\"sourceSha256\":\"" + "b".repeat(64) + "\"},"
                + "{\"skillId\":13,\"skillVersionId\":23,\"sourceSha256\":\"" + "c".repeat(64) + "\"}"
                + "]";
        byte[] exactBody = body.getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/internal/control/agent-skills/resolve-execution")
                        .contentType("application/json")
                        .content(exactBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].executable").value(true))
                .andExpect(jsonPath("$[0].reason").value("EXECUTABLE"))
                .andExpect(jsonPath("$[1].executable").value(true))
                .andExpect(jsonPath("$[2].executable").value(false))
                .andExpect(jsonPath("$[2].reason").value("STATUS_REVOKED"));

        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(authVerifier).requireRuntime(any(), bodyCaptor.capture());
        assertArrayEquals(exactBody, bodyCaptor.getValue());
    }

    @Test
    void malformedResolutionBodyUsesStableSkillErrorContract() throws Exception {
        byte[] body = "{not-json".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/internal/control/agent-skills/resolve-execution")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKILL_VERSION_STATE_INVALID"));

        verify(authVerifier).requireRuntime(any(), any(byte[].class));
        verify(catalogService, never()).version(any(), any());
    }

    @Test
    void missingReferenceDoesNotPoisonOtherExecutionResolutions() throws Exception {
        when(catalogService.version(11L, 21L)).thenReturn(version(21L, 11L, "PUBLISHED", "a"));
        when(catalogService.version(12L, 22L))
                .thenThrow(com.enterprise.ai.control.agentskill.AgentSkillException.notFound(
                        "Skill version not found: 12#22"));
        String body = "["
                + "{\"skillId\":11,\"skillVersionId\":21,\"sourceSha256\":\"" + "a".repeat(64) + "\"},"
                + "{\"skillId\":12,\"skillVersionId\":22,\"sourceSha256\":\"" + "b".repeat(64) + "\"}"
                + "]";

        mockMvc.perform(post("/internal/control/agent-skills/resolve-execution")
                        .contentType("application/json")
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].executable").value(true))
                .andExpect(jsonPath("$[0].reason").value("EXECUTABLE"))
                .andExpect(jsonPath("$[1].executable").value(false))
                .andExpect(jsonPath("$[1].status").value("MISSING"))
                .andExpect(jsonPath("$[1].sourceSha256").isEmpty())
                .andExpect(jsonPath("$[1].reason").value("NOT_FOUND"));
    }

    @Test
    void invalidReferenceIsReportedPerItemWithoutCatalogLookup() throws Exception {
        String body = "["
                + "{\"skillId\":11,\"skillVersionId\":21,\"sourceSha256\":\"not-a-digest\"},"
                + "{\"skillId\":null,\"skillVersionId\":22,\"sourceSha256\":\"" + "b".repeat(64) + "\"}"
                + "]";

        mockMvc.perform(post("/internal/control/agent-skills/resolve-execution")
                        .contentType("application/json")
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("INVALID_REFERENCE"))
                .andExpect(jsonPath("$[0].executable").value(false))
                .andExpect(jsonPath("$[0].reason").value("REFERENCE_INVALID"))
                .andExpect(jsonPath("$[1].status").value("INVALID_REFERENCE"))
                .andExpect(jsonPath("$[1].reason").value("REFERENCE_INVALID"));

        verify(catalogService, never()).version(any(), any());
    }

    @Test
    void packageDownloadRechecksExecutableStatusAndReturnsPinnedDigest() throws Exception {
        VersionView published = version(21L, 11L, "PUBLISHED", "d");
        SkillSummary skill = skill(11L, "demo-skill");
        byte[] archive = "immutable-zip".getBytes(StandardCharsets.UTF_8);
        when(catalogService.version(11L, 21L)).thenReturn(published);
        when(catalogService.detail(11L)).thenReturn(new SkillDetail(skill, List.of(published)));
        when(catalogService.packageBytes(11L, 21L)).thenReturn(archive);

        mockMvc.perform(get("/internal/control/agent-skills/11/versions/21/package"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string("X-ReachAI-Skill-SHA256", "d".repeat(64)))
                .andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"))
                .andExpect(content().bytes(archive));

        verify(authVerifier).requireRuntime(any(), any(byte[].class));
    }

    private SkillSummary skill(Long id, String name) {
        return new SkillSummary(
                id, "community", name, name, "Description", "PUBLIC",
                null, null, "ACTIVE", 21L, 21L, 1L, LocalDateTime.now(),
                "1.0.0", "PUBLISHED", "1.0.0");
    }

    private VersionView version(Long id, Long skillId, String status, String digestChar) {
        LocalDateTime now = LocalDateTime.now();
        return new VersionView(
                id, skillId, "1.0.0", status, "UPLOAD", "skill.zip",
                digestChar.repeat(64), "e".repeat(64), 100L,
                null, null, false, null, null, null, null, null,
                null, null, null, null, now, now);
    }
}
