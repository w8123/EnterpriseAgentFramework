package com.enterprise.ai.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.config.KnowledgeDependencyInfoContributor;
import com.enterprise.ai.controller.KnowledgeConsoleCatalogController;
import com.enterprise.ai.domain.dto.DocumentImportResourceScope;
import com.enterprise.ai.service.DocumentImportJobService;
import com.enterprise.ai.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeConsoleCatalogHttpTest {
    private static final String PATH = KnowledgeBizIndexConsoleAuthFilter.CATALOG_PREFIX + "/read";
    private static final String SECRET = "knowledge-catalog-fixture-secret-32bytes";
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final DocumentImportJobService scopes = mock(DocumentImportJobService.class);
    private final KnowledgeInternalNonceStore nonces = mock(KnowledgeInternalNonceStore.class);
    private final KnowledgeDependencyInfoContributor dependencies = mock(KnowledgeDependencyInfoContributor.class);

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new KnowledgeConsoleCatalogController(knowledge, scopes, dependencies))
                .addFilters(new KnowledgeBizIndexConsoleAuthFilter(SECRET, "", 300, 1_048_576, nonces)).build();
    }

    @Test void unsignedTamperedAndReplayedCommandsNeverReachTheOwner() throws Exception {
        var mvc = mvc();
        String body = "{\"operation\":\"BASES\"}";
        mvc.perform(post(PATH).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(signed(body).content("{\"operation\":\"FILES\"}")).andExpect(status().isUnauthorized());
        when(nonces.tryConsume(any(), any(), any(Duration.class))).thenReturn(false);
        mvc.perform(signed(body)).andExpect(status().isUnauthorized());
        verifyNoInteractions(knowledge, scopes, dependencies);
    }

    @Test void canonicalFileScopeAndContentTravelInTheSameSignedContract() throws Exception {
        when(nonces.tryConsume(any(), any(), any(Duration.class))).thenReturn(true);
        when(scopes.describeFileScope("file-1")).thenReturn(new DocumentImportResourceScope("team", "orders", "PROJECT"));
        when(knowledge.getChunksByFileId("file-1")).thenReturn(List.of());
        mvc().perform(signed("{\"operation\":\"FILE_CHUNKS\",\"fileId\":\"file-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.contractVersion").value(1))
                .andExpect(jsonPath("$.data.resource.projectCode").value("orders")).andExpect(jsonPath("$.data.data").isArray());
        verify(knowledge).getChunksByFileId("file-1");
    }

    @Test void onlyExplicitReadOperationsAndBoundedFiltersAreAccepted() throws Exception {
        when(nonces.tryConsume(any(), any(), any(Duration.class))).thenReturn(true);
        var mvc = mvc();
        for (String body : List.of("{\"operation\":\"DELETE\"}", "{\"operation\":\"BASES\",\"actorId\":\"1\"}",
                "{\"operation\":\"FILES\",\"kbCode\":\"../private\"}",
                "{\"operation\":\"CHUNKS\",\"kbCode\":\"orders\",\"limit\":501}",
                "{\"operation\":\"HIT_LOGS\",\"kbCode\":\"orders\",\"source\":\"CUSTOM\"}")) {
            mvc.perform(signed(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(knowledge, dependencies);
    }

    @Test void legacyCatalogRowsUseTheSameCanonicalDefaultsAsFileAndImportAuthorization() throws Exception {
        when(nonces.tryConsume(any(), any(), any(Duration.class))).thenReturn(true);
        var legacy = new com.enterprise.ai.domain.dto.KnowledgeBaseVO(); legacy.setCode("legacy");
        when(knowledge.listAll()).thenReturn(List.of(legacy));
        when(scopes.describeKnowledgeBaseScope("legacy")).thenReturn(new DocumentImportResourceScope("default", null, "WORKSPACE"));
        mvc().perform(signed("{\"operation\":\"BASES\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.data[0].workspaceId").value("default"))
                .andExpect(jsonPath("$.data.data[0].scope").value("WORKSPACE"));
    }

    @Test void settingsCommandsRequireExactBodySignaturesAndCannotSupplyAnActor() throws Exception {
        when(nonces.tryConsume(any(), any(), any(Duration.class))).thenReturn(true);
        var settings = mock(com.enterprise.ai.service.impl.KnowledgeSettingsService.class);
        when(settings.context("kb")).thenReturn(java.util.Map.of("contractVersion", 1, "revision", "a".repeat(64)));
        var controller = new com.enterprise.ai.controller.KnowledgeSettingsInternalController(settings);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new KnowledgeBizIndexConsoleAuthFilter(SECRET, "", 300, 1_048_576, nonces)).build();
        String contextPath = KnowledgeBizIndexConsoleAuthFilter.CATALOG_PREFIX + "/settings/context";
        mvc.perform(post(contextPath).contentType("application/json").content("{\"kbCode\":\"kb\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(signed("{\"kbCode\":\"kb\"}", contextPath)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractVersion").value(1));
        String savePath = KnowledgeBizIndexConsoleAuthFilter.CATALOG_PREFIX + "/settings/save";
        mvc.perform(signed("{\"kbCode\":\"kb\",\"baseRevision\":\"a\",\"changes\":{},\"actorId\":\"1\"}", savePath))
                .andExpect(status().isBadRequest());
        verify(settings, never()).save(any(), any(), any());
    }

    private MockHttpServletRequestBuilder signed(String body) {
        return signed(body, PATH);
    }
    private MockHttpServletRequestBuilder signed(String body, String path) {
        String timestamp = String.valueOf(System.currentTimeMillis()), nonce = UUID.randomUUID().toString();
        String digest = InternalServiceHmac.bodySha256Hex(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String canonical = InternalServiceHmac.canonical("POST", path, "reachai-control-service", "PLATFORM_SESSION", "default", "7", timestamp, nonce, digest);
        return post(path).contentType("application/json").content(body)
                .header(InternalServiceAuthHeaders.CALLER, "reachai-control-service")
                .header(InternalServiceAuthHeaders.IDENTITY_SOURCE, "PLATFORM_SESSION")
                .header(InternalServiceAuthHeaders.IDENTITY_TENANT_ID, "default").header(InternalServiceAuthHeaders.IDENTITY_USER_ID, "7")
                .header(InternalServiceAuthHeaders.TIMESTAMP, timestamp).header(InternalServiceAuthHeaders.NONCE, nonce)
                .header(InternalServiceAuthHeaders.BODY_SHA256, digest).header(InternalServiceAuthHeaders.SIGNATURE, InternalServiceHmac.sign(SECRET, canonical));
    }
}
