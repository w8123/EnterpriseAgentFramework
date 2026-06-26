package com.enterprise.ai.agent.platform.control.identity;

import com.enterprise.ai.agent.platform.control.identity.PageActionCatalogContracts.PageActionDefinitionRequest;
import com.enterprise.ai.agent.platform.control.identity.PageActionCatalogContracts.PageCatalogRegisterRequest;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PageActionCatalogServiceTest {

    private final PageRegistryMapper pageMapper = mock(PageRegistryMapper.class);
    private final PageActionRegistryMapper actionMapper = mock(PageActionRegistryMapper.class);
    private final PageActionCatalogService service = new PageActionCatalogService(pageMapper, actionMapper, new ObjectMapper());

    @Test
    void registerFromProjectCredentialPersistsPageAndActions() {
        RegistryCredentialEntity credential = credential("qmssmp-teams-construction-service");
        PageCatalogRegisterRequest request = new PageCatalogRegisterRequest(
                "team-archive",
                "班组档案",
                "/team-build/depart-management",
                "http://localhost:9200",
                "page-001",
                true,
                List.of(new PageActionDefinitionRequest(
                        "qmssmp.teamArchive.search",
                        "查询班组档案",
                        "回填筛选条件并触发查询",
                        false,
                        Map.of("type", "object"),
                        Map.of("type", "object"),
                        Map.of("managerName", "靳圣辉"),
                        List.of("team-archive-assistant"),
                        Map.of("source", "sdk"))),
                Map.of("framework", "angular"));

        PageCatalogRegisterResult result = service.registerFromProjectCredential(credential, request);

        assertEquals("team-archive", result.pageKey());
        assertEquals(1, result.actionCount());
        ArgumentCaptor<PageRegistryEntity> pageCaptor = ArgumentCaptor.forClass(PageRegistryEntity.class);
        verify(pageMapper).upsert(pageCaptor.capture());
        assertEquals("http://localhost:9200", pageCaptor.getValue().getOrigin());
        verify(actionMapper).upsert(any(PageActionRegistryEntity.class));
        verify(actionMapper).markMissingRemoved(eq("qmssmp-teams-construction-service"), eq("qmssmp-teams-construction-service"),
                eq("team-archive"), eq(List.of("qmssmp.teamArchive.search")));
    }

    @Test
    void registerFromProjectCredentialNormalizesMissingOriginForUniqueUpsert() {
        RegistryCredentialEntity credential = credential("qmssmp-teams-construction-service");
        PageCatalogRegisterRequest request = new PageCatalogRegisterRequest(
                "teamArchive.list",
                "班组档案列表",
                "/team-build/depart-management",
                null,
                "page-001",
                true,
                List.of(),
                Map.of());

        service.registerFromProjectCredential(credential, request);

        ArgumentCaptor<PageRegistryEntity> pageCaptor = ArgumentCaptor.forClass(PageRegistryEntity.class);
        verify(pageMapper).upsert(pageCaptor.capture());
        assertEquals("", pageCaptor.getValue().getOrigin());
    }

    @Test
    void registerFromProjectCredentialRejectsMissingActionKey() {
        RegistryCredentialEntity credential = credential("qmssmp-teams-construction-service");
        PageCatalogRegisterRequest request = new PageCatalogRegisterRequest(
                "team-archive",
                "班组档案",
                "/team-build/depart-management",
                "http://localhost:9200",
                null,
                true,
                List.of(new PageActionDefinitionRequest(
                        "",
                        "查询班组档案",
                        "",
                        false,
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        List.of(),
                        Map.of())),
                Map.of());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.registerFromProjectCredential(credential, request));

        assertEquals("page action key is required", ex.getMessage());
    }

    private RegistryCredentialEntity credential(String projectCode) {
        RegistryCredentialEntity entity = new RegistryCredentialEntity();
        entity.setProjectId(1L);
        entity.setProjectCode(projectCode);
        entity.setAppKey(projectCode);
        entity.setAppSecret("dev-secret");
        entity.setStatus("ACTIVE");
        return entity;
    }
}
