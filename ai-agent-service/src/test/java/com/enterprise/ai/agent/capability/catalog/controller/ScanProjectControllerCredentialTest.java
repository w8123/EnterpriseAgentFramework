package com.enterprise.ai.agent.capability.catalog.controller;

import com.enterprise.ai.agent.registry.AiRegistryService;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.agent.registry.SdkAccessCheckService;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleService;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectService;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolService;
import com.enterprise.ai.agent.capability.catalog.scan.sensitive.SensitiveDataScanOrchestrator;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScanProjectControllerCredentialTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScanProjectService scanProjectService = mock(ScanProjectService.class);
    private final RegistrySecurityService registrySecurityService = mock(RegistrySecurityService.class);
    private final SdkAccessCheckService sdkAccessCheckService = mock(SdkAccessCheckService.class);
    private final ScanProjectController controller = new ScanProjectController(
            objectMapper,
            scanProjectService,
            mock(ToolDefinitionService.class),
            mock(ScanModuleService.class),
            mock(ScanProjectToolService.class),
            mock(AiRegistryService.class),
            registrySecurityService,
            sdkAccessCheckService,
            mock(SensitiveDataScanOrchestrator.class));

    @Test
    void scanProjectDetailStillIncludesRegistryCredentialForLegacyPageRegistryConfig() {
        ScanProjectEntity project = project();
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("qmssmp-teams-construction-service");
        credential.setAppSecret("dev-secret");
        when(scanProjectService.getById(1L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential("qmssmp-teams-construction-service"))
                .thenReturn(Optional.of(credential));

        ResponseEntity<?> response = controller.get(1L);
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });

        assertEquals("qmssmp-teams-construction-service", body.get("registryAppKey"));
        assertEquals("dev-secret", body.get("registryAppSecret"));
    }

    @Test
    void registeredProjectDetailDoesNotExposeRegistryCredentialSecret() {
        ScanProjectEntity project = project();
        project.setProjectKind("REGISTERED");
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("qmssmp-teams-construction-service");
        credential.setAppSecret("dev-secret");
        when(scanProjectService.getById(1L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential("qmssmp-teams-construction-service"))
                .thenReturn(Optional.of(credential));

        ResponseEntity<?> response = controller.get(1L);
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });

        assertEquals("qmssmp-teams-construction-service", body.get("registryAppKey"));
        assertEquals(Boolean.TRUE, body.get("registryCredentialConfigured"));
        assertNull(body.get("registryAppSecret"));
    }

    @Test
    void listDoesNotExposeRegistryCredentialSecrets() {
        when(scanProjectService.list()).thenReturn(List.of(project()));

        ResponseEntity<List<?>> response = (ResponseEntity<List<?>>) (ResponseEntity<?>) controller.list();
        Map<String, Object> body = objectMapper.convertValue(response.getBody().get(0), new TypeReference<>() {
        });

        assertNull(body.get("registryAppKey"));
        assertNull(body.get("registryAppSecret"));
    }

    @Test
    void updateRegistryCredentialSavesPrimaryCredentialAndDoesNotReturnFreshSecretForRegisteredProject() {
        ScanProjectEntity project = project();
        project.setProjectKind("REGISTERED");
        RegistryCredentialEntity saved = new RegistryCredentialEntity();
        saved.setAppKey("updated-key");
        saved.setAppSecret("updated-secret");
        when(scanProjectService.getById(1L)).thenReturn(project);
        when(registrySecurityService.savePrimaryCredential(
                1L,
                "qmssmp-teams-construction-service",
                "updated-key",
                "updated-secret"))
                .thenReturn(saved);
        when(registrySecurityService.findPrimaryActiveCredential("qmssmp-teams-construction-service"))
                .thenReturn(Optional.of(saved));

        ResponseEntity<?> response = controller.updateRegistryCredential(
                1L,
                new ScanProjectController.RegistryCredentialUpdateRequest("updated-key", "updated-secret"));
        Map<String, Object> body = objectMapper.convertValue(response.getBody(), new TypeReference<>() {
        });

        assertEquals("updated-key", body.get("registryAppKey"));
        assertEquals(Boolean.TRUE, body.get("registryCredentialConfigured"));
        assertNull(body.get("registryAppSecret"));
        verify(registrySecurityService).savePrimaryCredential(
                1L,
                "qmssmp-teams-construction-service",
                "updated-key",
                "updated-secret");
    }

    @Test
    void sdkAccessCheckDelegatesToService() {
        var request = new SdkAccessCheckService.SdkAccessCheckRequest(
                10L,
                Map.of("teamName", "一班"),
                "http://localhost:8080",
                "/api/reachai/embed-token");
        var serviceResponse = new SdkAccessCheckService.SdkAccessCheckResponse(
                1L,
                "qmssmp-teams-construction-service",
                SdkAccessCheckService.CheckStatus.PASS,
                List.of(),
                List.of(new SdkAccessCheckService.SdkAccessCheckItem(
                        "api-invocation",
                        "最终接口自检",
                        SdkAccessCheckService.CheckStatus.PASS,
                        "平台已完成一次业务接口调用。",
                        "{ok=true}")));
        when(sdkAccessCheckService.check(1L, request)).thenReturn(serviceResponse);

        ResponseEntity<?> response = controller.sdkAccessCheck(1L, request);

        assertEquals(serviceResponse, response.getBody());
        verify(sdkAccessCheckService).check(1L, request);
    }

    private ScanProjectEntity project() {
        ScanProjectEntity entity = new ScanProjectEntity();
        entity.setId(1L);
        entity.setName("班组建设服务");
        entity.setProjectCode("qmssmp-teams-construction-service");
        entity.setProjectKind("SCAN");
        entity.setEnvironment("dev");
        entity.setVisibility("PRIVATE");
        entity.setBaseUrl("http://localhost:8080");
        entity.setContextPath("");
        entity.setScanPath("");
        entity.setScanType("controller");
        entity.setToolCount(0);
        entity.setStatus("created");
        return entity;
    }
}
