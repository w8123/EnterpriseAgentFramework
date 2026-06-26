package com.enterprise.ai.agent.platform.control.controller;

import com.enterprise.ai.agent.platform.control.identity.EmbedAuditEventService;
import com.enterprise.ai.agent.platform.control.identity.EmbedChatEventMapper;
import com.enterprise.ai.agent.platform.control.identity.EmbedRendererMapper;
import com.enterprise.ai.agent.platform.control.identity.EmbedSessionEntity;
import com.enterprise.ai.agent.platform.control.identity.EmbedSessionMapper;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenRevocationService;
import com.enterprise.ai.agent.platform.control.identity.PageActionEventMapper;
import com.enterprise.ai.agent.platform.control.identity.PageActionEventEntity;
import com.enterprise.ai.agent.platform.control.identity.PageActionRegistryEntity;
import com.enterprise.ai.agent.platform.control.identity.PageActionRegistryMapper;
import com.enterprise.ai.agent.platform.control.identity.PageRegistryEntity;
import com.enterprise.ai.agent.platform.control.identity.PageRegistryManagementService;
import com.enterprise.ai.agent.platform.control.identity.PageRegistryMapper;
import com.enterprise.ai.agent.platform.control.controller.PlatformEmbedOpsController;
import com.enterprise.ai.agent.registry.RegistryCredentialMapper;
import com.enterprise.ai.agent.workflow.WorkflowActionReferenceService;
import com.enterprise.ai.common.dto.ApiResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class PlatformEmbedOpsControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void pageActionReferencesReturnsWorkflowReferenceDiagnostics() {
        PageActionRegistryMapper actionMapper = mock(PageActionRegistryMapper.class);
        PageActionEventMapper eventMapper = mock(PageActionEventMapper.class);
        WorkflowActionReferenceService referenceService = mock(WorkflowActionReferenceService.class);
        PlatformEmbedOpsController controller = newController(actionMapper, eventMapper, referenceService);

        PageActionRegistryEntity action = new PageActionRegistryEntity();
        action.setId(10L);
        action.setProjectCode("team-system");
        action.setPageKey("teamArchive.list");
        action.setActionKey("teamArchive.search");
        when(actionMapper.selectById(10L)).thenReturn(action);
        when(referenceService.findReferences(action)).thenReturn(List.of(
                new WorkflowActionReferenceService.PageActionWorkflowReference(
                        "workflow-1",
                        "team-archive-workflow",
                        "班组档案页面流程",
                        "team-system",
                        "ACTIVE",
                        9L,
                        "v9",
                        "ACTIVE_VERSION",
                        "node-page-action",
                        "查询班组档案",
                        "team-system",
                        "teamArchive.list",
                        "teamArchive.search",
                        "agent-1",
                        "team-global-ai",
                        "班组全局助手",
                        "team-system",
                        true,
                        7L,
                        "PAGE",
                        true)));

        ApiResult<List<PlatformEmbedOpsController.PageActionReferenceView>> response =
                controller.pageActionReferences(10L);

        assertEquals(1, response.getData().size());
        PlatformEmbedOpsController.PageActionReferenceView reference = response.getData().get(0);
        assertEquals("agent-1", reference.agentId());
        assertEquals("team-global-ai", reference.agentKeySlug());
        assertEquals("workflow-1", reference.workflowId());
        assertEquals("team-archive-workflow", reference.workflowKeySlug());
        assertEquals(9L, reference.workflowVersionId());
        assertEquals("ACTIVE_VERSION", reference.graphSource());
        assertEquals(7L, reference.bindingId());
        assertEquals("PAGE", reference.bindingType());
        assertTrue(reference.bindingEnabled());
        assertEquals("node-page-action", reference.nodeId());
        assertEquals("teamArchive.search", reference.actionKey());
        verify(referenceService).findReferences(action);
    }

    @Test
    void pageActionDebugResultReturnsRecordedEvent() {
        PageActionEventMapper eventMapper = mock(PageActionEventMapper.class);
        PlatformEmbedOpsController controller = newController(
                mock(PageActionRegistryMapper.class),
                eventMapper,
                mock(WorkflowActionReferenceService.class));
        PageActionEventEntity event = new PageActionEventEntity();
        event.setRequestId("debug-1");
        event.setStatus("SUCCESS");
        event.setResultJson("{\"ok\":true}");
        when(eventMapper.selectOne(any())).thenReturn(event);

        ApiResult<PageActionEventEntity> response = controller.pageActionDebugResult("debug-1");

        assertEquals("SUCCESS", response.getData().getStatus());
        assertEquals("{\"ok\":true}", response.getData().getResultJson());
    }

    @Test
    void debugPageActionCreatesRequestForCurrentRegisteredPageInstance() {
        EmbedSessionMapper sessionMapper = mock(EmbedSessionMapper.class);
        PageRegistryMapper pageMapper = mock(PageRegistryMapper.class);
        PageActionRegistryMapper actionMapper = mock(PageActionRegistryMapper.class);
        EmbedAuditEventService auditEventService = mock(EmbedAuditEventService.class);
        PlatformEmbedOpsController controller = newController(
                sessionMapper,
                pageMapper,
                actionMapper,
                mock(PageActionEventMapper.class),
                mock(WorkflowActionReferenceService.class),
                auditEventService);

        PageActionRegistryEntity action = new PageActionRegistryEntity();
        action.setId(12L);
        action.setProjectCode("qmssmp-teams-construction-service");
        action.setPageKey("teamArchive.list");
        action.setActionKey("qmssmp.teamArchive.search");
        action.setTitle("查询班组档案");
        action.setStatus("ACTIVE");
        when(actionMapper.selectById(12L)).thenReturn(action);

        PageRegistryEntity page = new PageRegistryEntity();
        page.setProjectCode("qmssmp-teams-construction-service");
        page.setPageKey("teamArchive.list");
        page.setStatus("ACTIVE");
        page.setCurrentPageInstanceId("page-instance-1");
        when(pageMapper.selectOne(any())).thenReturn(page);

        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setSessionId("embed-session-1");
        session.setProjectCode("qmssmp-teams-construction-service");
        session.setAppId("qmssmp-teams-construction-service");
        session.setAgentId("team-archive-assistant");
        session.setPageInstanceId("page-instance-1");
        session.setStatus("ACTIVE");
        when(sessionMapper.selectOne(any())).thenReturn(session);

        ApiResult<PlatformEmbedOpsController.PageActionDebugResponse> response = controller.debugPageAction(
                12L,
                new PlatformEmbedOpsController.PageActionDebugRequest(null, Map.of("managerName", "靳圣辉")));

        assertEquals("REQUESTED", response.getData().status());
        assertEquals("embed-session-1", response.getData().sessionId());
        assertEquals("page-instance-1", response.getData().targetPageInstanceId());
        assertNotNull(response.getData().requestId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditEventService).recordPageActionDebugRequest(any(EmbedSessionEntity.class), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals("page.action.requested", payload.get("type"));
        assertEquals("qmssmp.teamArchive.search", payload.get("actionKey"));
        assertEquals("查询班组档案", payload.get("title"));
        assertEquals(Map.of("managerName", "靳圣辉"), payload.get("args"));
        assertEquals(Map.of("pageInstanceId", "page-instance-1"), payload.get("target"));
    }

    @Test
    void manuallyDeclaresPageActionCatalogEntry() {
        PageRegistryMapper pageMapper = mock(PageRegistryMapper.class);
        PageActionRegistryMapper actionMapper = mock(PageActionRegistryMapper.class);
        PlatformEmbedOpsController controller = newController(
                mock(EmbedSessionMapper.class),
                pageMapper,
                actionMapper,
                mock(PageActionEventMapper.class),
                mock(WorkflowActionReferenceService.class),
                mock(EmbedAuditEventService.class));

        ApiResult<PlatformEmbedOpsController.PageActionManualDeclareResponse> response =
                controller.declarePageActionCatalog(new PlatformEmbedOpsController.PageActionManualDeclareRequest(
                        "qmssmp-teams-construction-service",
                        "qmssmp",
                        "teamArchive.list",
                        "班组档案",
                        "/teams/archive",
                        "qmssmp.teamArchive.search",
                        "查询班组档案",
                        "按班组名称筛选档案列表",
                        false,
                        Map.of(
                                "type", "object",
                                "required", List.of("teamName"),
                                "properties", Map.of("teamName", Map.of("type", "string", "description", "班组名称"))),
                        Map.of("type", "object"),
                        Map.of("teamName", "一班"),
                        List.of(),
                        "ACTIVE"));

        assertEquals("MANUAL_DRAFT", response.getData().source());
        assertEquals("teamArchive.list", response.getData().page().getPageKey());
        assertEquals("qmssmp.teamArchive.search", response.getData().action().getActionKey());
        assertEquals("ACTIVE", response.getData().action().getStatus());

        ArgumentCaptor<PageRegistryEntity> pageCaptor = ArgumentCaptor.forClass(PageRegistryEntity.class);
        verify(pageMapper).upsert(pageCaptor.capture());
        assertEquals("qmssmp-teams-construction-service", pageCaptor.getValue().getProjectCode());
        assertEquals("teamArchive.list", pageCaptor.getValue().getPageKey());
        assertTrue(pageCaptor.getValue().getMetadataJson().contains("MANUAL_DRAFT"));

        ArgumentCaptor<PageActionRegistryEntity> actionCaptor = ArgumentCaptor.forClass(PageActionRegistryEntity.class);
        verify(actionMapper).upsert(actionCaptor.capture());
        assertEquals("qmssmp.teamArchive.search", actionCaptor.getValue().getActionKey());
        assertEquals("{\"teamName\":\"一班\"}", actionCaptor.getValue().getSampleArgsJson());
        assertTrue(actionCaptor.getValue().getMetadataJson().contains("MANUAL_DRAFT"));
    }

    @Test
    void deletePageDelegatesToManagementService() {
        PageRegistryManagementService managementService = mock(PageRegistryManagementService.class);
        PlatformEmbedOpsController controller = newController(
                mock(PageActionRegistryMapper.class),
                mock(PageActionEventMapper.class),
                mock(WorkflowActionReferenceService.class),
                managementService);
        PageRegistryManagementService.PageRegistryDeleteResult result =
                new PageRegistryManagementService.PageRegistryDeleteResult(
                        7L, "team-system", "teamArchive.list", 1, 2, 1, 2, 3, 1, 4);
        when(managementService.deletePageAndRelations(7L)).thenReturn(result);

        ApiResult<PageRegistryManagementService.PageRegistryDeleteResult> response = controller.deletePage(7L);

        assertEquals("teamArchive.list", response.getData().pageKey());
        assertEquals(2, response.getData().deletedActions());
        verify(managementService).deletePageAndRelations(7L);
    }

    private PlatformEmbedOpsController newController(PageActionRegistryMapper actionMapper,
                                                    PageActionEventMapper eventMapper,
                                                    WorkflowActionReferenceService referenceService) {
        return newController(actionMapper, eventMapper, referenceService, mock(PageRegistryManagementService.class));
    }

    private PlatformEmbedOpsController newController(PageActionRegistryMapper actionMapper,
                                                    PageActionEventMapper eventMapper,
                                                    WorkflowActionReferenceService referenceService,
                                                    PageRegistryManagementService managementService) {
        return new PlatformEmbedOpsController(
                mock(EmbedSessionMapper.class),
                eventMapper,
                mock(EmbedChatEventMapper.class),
                mock(EmbedTokenRevocationService.class),
                mock(EmbedRendererMapper.class),
                mock(RegistryCredentialMapper.class),
                mock(PageRegistryMapper.class),
                actionMapper,
                managementService,
                referenceService,
                mock(EmbedAuditEventService.class),
                objectMapper);
    }

    private PlatformEmbedOpsController newController(EmbedSessionMapper sessionMapper,
                                                    PageRegistryMapper pageMapper,
                                                    PageActionRegistryMapper actionMapper,
                                                    PageActionEventMapper eventMapper,
                                                    WorkflowActionReferenceService referenceService,
                                                    EmbedAuditEventService auditEventService) {
        return new PlatformEmbedOpsController(
                sessionMapper,
                eventMapper,
                mock(EmbedChatEventMapper.class),
                mock(EmbedTokenRevocationService.class),
                mock(EmbedRendererMapper.class),
                mock(RegistryCredentialMapper.class),
                pageMapper,
                actionMapper,
                mock(PageRegistryManagementService.class),
                referenceService,
                auditEventService,
                objectMapper);
    }
}
