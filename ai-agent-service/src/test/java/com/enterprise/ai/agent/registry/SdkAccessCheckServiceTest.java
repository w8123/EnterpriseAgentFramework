package com.enterprise.ai.agent.registry;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectService;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SdkAccessCheckServiceTest {

    @Mock
    private ScanProjectService scanProjectService;
    @Mock
    private RegistrySecurityService registrySecurityService;
    @Mock
    private AiRegistryService aiRegistryService;
    @Mock
    private ScanProjectToolService scanProjectToolService;
    @InjectMocks
    private SdkAccessCheckService service;

    @Test
    void registeredProjectReportsReadyChecksAndExecutesSelectedApiAsset() {
        ScanProjectEntity project = registeredProject();
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("qmssmp-teams-construction-service");
        ProjectInstanceEntity instance = onlineInstance();
        ScanProjectToolEntity tool = apiTool(10L);
        when(scanProjectService.getById(1L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential("qmssmp-teams-construction-service"))
                .thenReturn(Optional.of(credential));
        when(aiRegistryService.listInstances("qmssmp-teams-construction-service")).thenReturn(List.of(instance));
        when(scanProjectToolService.listByProject(1L)).thenReturn(List.of(tool));
        when(scanProjectToolService.execute(1L, 10L, Map.of("teamName", "一班")))
                .thenReturn(Map.of("ok", true));

        SdkAccessCheckService.SdkAccessCheckResponse response = service.check(
                1L,
                new SdkAccessCheckService.SdkAccessCheckRequest(
                        10L,
                        Map.of("teamName", "一班"),
                        "http://localhost:8080",
                        "/api/reachai/embed-token"));

        assertEquals(SdkAccessCheckService.CheckStatus.PASS, response.overallStatus());
        assertEquals(SdkAccessCheckService.CheckStatus.PASS, statusOf(response, "project-kind"));
        assertEquals(SdkAccessCheckService.CheckStatus.PASS, statusOf(response, "registry-credential"));
        assertEquals(SdkAccessCheckService.CheckStatus.PASS, statusOf(response, "online-instance"));
        assertEquals(SdkAccessCheckService.CheckStatus.PASS, statusOf(response, "api-assets"));
        assertEquals(SdkAccessCheckService.CheckStatus.PASS, statusOf(response, "gateway-route"));
        assertEquals(SdkAccessCheckService.CheckStatus.PASS, statusOf(response, "embed-token"));
        assertEquals(SdkAccessCheckService.CheckStatus.PASS, statusOf(response, "api-invocation"));
        assertEquals(SdkAccessCheckService.CheckStatus.PASS, readinessOf(response, "CODE_READY"));
        assertEquals(SdkAccessCheckService.CheckStatus.PASS, readinessOf(response, "RUNTIME_READY"));
        assertEquals(SdkAccessCheckService.CheckStatus.PASS, readinessOf(response, "E2E_READY"));
        assertTrue(response.checks().stream()
                .filter(item -> "api-invocation".equals(item.key()))
                .findFirst()
                .orElseThrow()
                .evidence()
                .contains("ok=true"));
    }

    @Test
    void readinessSeparatesCodeReadyFromRuntimeAndE2EWarnings() {
        ScanProjectEntity project = registeredProject();
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("qmssmp-teams-construction-service");
        when(scanProjectService.getById(1L)).thenReturn(project);
        when(registrySecurityService.findPrimaryActiveCredential("qmssmp-teams-construction-service"))
                .thenReturn(Optional.of(credential));
        when(aiRegistryService.listInstances("qmssmp-teams-construction-service")).thenReturn(List.of());
        when(scanProjectToolService.listByProject(1L)).thenReturn(List.of());

        SdkAccessCheckService.SdkAccessCheckResponse response = service.check(
                1L,
                new SdkAccessCheckService.SdkAccessCheckRequest(
                        null,
                        Map.of(),
                        "http://localhost:8080",
                        "/api/reachai/embed-token"));

        assertEquals(SdkAccessCheckService.CheckStatus.WARN, response.overallStatus());
        assertEquals(SdkAccessCheckService.CheckStatus.PASS, readinessOf(response, "CODE_READY"));
        assertEquals(SdkAccessCheckService.CheckStatus.WARN, readinessOf(response, "RUNTIME_READY"));
        assertEquals(SdkAccessCheckService.CheckStatus.WARN, readinessOf(response, "E2E_READY"));
    }

    @Test
    void scanProjectIsReportedAsNotApplicable() {
        ScanProjectEntity project = registeredProject();
        project.setProjectKind("SCAN");
        when(scanProjectService.getById(1L)).thenReturn(project);

        SdkAccessCheckService.SdkAccessCheckResponse response = service.check(
                1L,
                new SdkAccessCheckService.SdkAccessCheckRequest(null, Map.of(), null, null));

        assertEquals(SdkAccessCheckService.CheckStatus.FAIL, response.overallStatus());
        assertEquals(SdkAccessCheckService.CheckStatus.FAIL, statusOf(response, "project-kind"));
        assertTrue(response.checks().get(0).message().contains("SDK"));
    }

    private static SdkAccessCheckService.CheckStatus statusOf(
            SdkAccessCheckService.SdkAccessCheckResponse response,
            String key) {
        return response.checks().stream()
                .filter(item -> key.equals(item.key()))
                .findFirst()
                .orElseThrow()
                .status();
    }

    private static SdkAccessCheckService.CheckStatus readinessOf(
            SdkAccessCheckService.SdkAccessCheckResponse response,
            String key) {
        return response.readiness().stream()
                .filter(item -> key.equals(item.key()))
                .findFirst()
                .orElseThrow()
                .status();
    }

    private static ScanProjectEntity registeredProject() {
        ScanProjectEntity entity = new ScanProjectEntity();
        entity.setId(1L);
        entity.setName("班组建设服务");
        entity.setProjectCode("qmssmp-teams-construction-service");
        entity.setProjectKind("REGISTERED");
        entity.setBaseUrl("http://localhost:8080");
        entity.setContextPath("");
        entity.setToolCount(1);
        entity.setStatus("created");
        return entity;
    }

    private static ProjectInstanceEntity onlineInstance() {
        ProjectInstanceEntity entity = new ProjectInstanceEntity();
        entity.setId(1L);
        entity.setProjectId(1L);
        entity.setProjectCode("qmssmp-teams-construction-service");
        entity.setInstanceId("dev-1");
        entity.setStatus("ONLINE");
        entity.setSdkVersion("1.0.0");
        entity.setLastHeartbeatAt(LocalDateTime.now());
        return entity;
    }

    private static ScanProjectToolEntity apiTool(Long id) {
        ScanProjectToolEntity entity = new ScanProjectToolEntity();
        entity.setId(id);
        entity.setProjectId(1L);
        entity.setName("qmssmp.teamArchive.search");
        entity.setHttpMethod("POST");
        entity.setEndpointPath("/reachai/capabilities/teamArchive/search");
        entity.setEnabled(true);
        entity.setAgentVisible(true);
        return entity;
    }
}
