package com.enterprise.ai.agent.platform.control.context.runtime;

import com.enterprise.ai.agent.platform.control.context.ContextComposerService;
import com.enterprise.ai.agent.platform.control.context.ContextItemResponse;
import com.enterprise.ai.agent.platform.control.context.ContextItemType;
import com.enterprise.ai.agent.platform.control.context.ContextPackageComposeRequest;
import com.enterprise.ai.agent.platform.control.context.ContextPackageResponse;
import com.enterprise.ai.agent.platform.control.context.ContextSearchResult;
import com.enterprise.ai.agent.platform.control.context.MemoryLane;
import com.enterprise.ai.agent.platform.control.identity.EmbedSessionEntity;
import com.enterprise.ai.agent.platform.control.identity.EmbedTokenClaims;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeContextPackageServiceTest {

    private ContextComposerService composerService;
    private RuntimeContextProperties properties;
    private RuntimeContextPackageService service;

    @BeforeEach
    void setUp() {
        composerService = mock(ContextComposerService.class);
        properties = new RuntimeContextProperties();
        properties.setEnabled(true);
        properties.setMaxItems(8);
        properties.setTokenBudget(1200);
        service = new RuntimeContextPackageService(
                composerService,
                new RuntimeContextPromptFormatter(properties),
                properties);
    }

    @Test
    void composeRequestUsesRuntimeUserLaneAndTopK() {
        when(composerService.compose(any())).thenReturn(samplePackage("user-a"));

        RuntimeContextIdentity identity = baseIdentity("default", "user-a");
        identity.setRuntimePlacement("CENTRAL");
        service.inject(identity);

        ArgumentCaptor<ContextPackageComposeRequest> captor = ArgumentCaptor.forClass(ContextPackageComposeRequest.class);
        verify(composerService).compose(captor.capture());
        ContextPackageComposeRequest composeRequest = captor.getValue();
        assertEquals(MemoryLane.RUNTIME_USER.name(), composeRequest.getQuery().getMemoryLane());
        assertEquals(8, composeRequest.getMaxItems());
        assertEquals(8, composeRequest.getQuery().getTopK());
        assertEquals("HYBRID", composeRequest.getQuery().getRetrievalMode());
        assertEquals(1200, composeRequest.getTokenBudget());
        assertEquals("user-a", composeRequest.getQuery().getUserId());
        assertEquals("USER", composeRequest.getQuery().getActorType());
        assertEquals("user-a", composeRequest.getQuery().getActorId());
    }

    @Test
    void composeRequestUsesCurrentUserMessageAsHybridQuery() {
        when(composerService.compose(any())).thenReturn(samplePackage("user-a"));

        RuntimeContextIdentity identity = baseIdentity("default", "user-a");
        identity.setRuntimePlacement("CENTRAL");
        identity.setQuery("深色 模式");
        service.inject(identity);

        ArgumentCaptor<ContextPackageComposeRequest> captor = ArgumentCaptor.forClass(ContextPackageComposeRequest.class);
        verify(composerService).compose(captor.capture());
        assertEquals("深色 模式", captor.getValue().getQuery().getQuery());
        assertEquals("HYBRID", captor.getValue().getQuery().getRetrievalMode());
    }

    @Test
    void buildIdentityCapturesRuntimeContextQueryFromEmbedMetadata() {
        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setSessionId("sess-1");
        session.setTenantId("default");
        session.setProjectCode("demo-project");
        session.setExternalUserId("external-a");
        session.setGlobalUserId("global-a");
        session.setPageInstanceId("page-1");

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("runtimeContextQuery", "深色 模式");

        RuntimeContextIdentity identity = service.buildIdentity(session, null, metadata);

        assertEquals("深色 模式", identity.getQuery());
    }

    @Test
    void globalUserIdPreferredOverExternalUserId() {
        when(composerService.compose(any())).thenReturn(samplePackage("global-a"));

        RuntimeContextIdentity identity = baseIdentity("default", null);
        identity.setGlobalUserId("global-a");
        identity.setExternalUserId("external-a");
        identity.setRuntimePlacement("CENTRAL");
        service.inject(identity);

        ArgumentCaptor<ContextPackageComposeRequest> captor = ArgumentCaptor.forClass(ContextPackageComposeRequest.class);
        verify(composerService).compose(captor.capture());
        assertEquals("global-a", captor.getValue().getQuery().getUserId());
    }

    @Test
    void externalUserIdPreferredOverRequestUserId() {
        when(composerService.compose(any())).thenReturn(samplePackage("external-a"));

        RuntimeContextIdentity identity = RuntimeContextIdentity.builder()
                .tenantId("default")
                .projectCode("demo-project")
                .userId("platform-user")
                .externalUserId("external-a")
                .sessionId("sess-1")
                .traceId("trace-1")
                .runtimePlacement("CENTRAL")
                .build();
        service.inject(identity);

        ArgumentCaptor<ContextPackageComposeRequest> captor = ArgumentCaptor.forClass(ContextPackageComposeRequest.class);
        verify(composerService).compose(captor.capture());
        assertEquals("external-a", captor.getValue().getQuery().getUserId());
    }

    @Test
    void defaultTenantIsValidAndNotSkipped() {
        when(composerService.compose(any())).thenReturn(samplePackage("user-a"));

        RuntimeContextIdentity identity = baseIdentity("default", "user-a");
        identity.setRuntimePlacement("CENTRAL");
        RuntimeContextInjectionResult result = service.inject(identity);

        assertTrue(result.isEnabled());
        verify(composerService).compose(any());
    }

    @Test
    void missingTenantIdSkipsWithoutThrowing() {
        RuntimeContextIdentity identity = baseIdentity(null, "user-a");
        identity.setRuntimePlacement("CENTRAL");

        RuntimeContextInjectionResult result = service.inject(identity);

        assertFalse(result.isEnabled());
        assertEquals("missing-tenant-id", result.getSkippedReason());
        verify(composerService, never()).compose(any());
    }

    @Test
    void embeddedPlacementSkipsInjection() {
        RuntimeContextIdentity identity = baseIdentity("default", "user-a");
        identity.setRuntimePlacement("EMBEDDED");

        RuntimeContextInjectionResult result = service.inject(identity);

        assertFalse(result.isEnabled());
        assertEquals("embedded-placement", result.getSkippedReason());
        verify(composerService, never()).compose(any());
    }

    @Test
    void centralPlacementEnablesInjection() {
        when(composerService.compose(any())).thenReturn(samplePackage("user-a"));

        RuntimeContextIdentity identity = baseIdentity("default", "user-a");
        identity.setRuntimePlacement("CENTRAL");
        RuntimeContextInjectionResult result = service.inject(identity);

        assertTrue(result.isEnabled());
        assertNotNull(result.getPromptSection());
        assertTrue(result.getPromptSection().contains("[ReachAI Runtime Context]"));
    }

    @Test
    void composeExceptionReturnsSkippedReason() {
        when(composerService.compose(any())).thenThrow(new IllegalArgumentException("db unavailable"));

        RuntimeContextIdentity identity = baseIdentity("default", "user-a");
        identity.setRuntimePlacement("CENTRAL");
        RuntimeContextInjectionResult result = service.inject(identity);

        assertFalse(result.isEnabled());
        assertTrue(result.getSkippedReason().startsWith("context-error:"));
    }

    @Test
    void emptyPackageSkipsInjection() {
        when(composerService.compose(any())).thenReturn(ContextPackageResponse.builder()
                .memoryLane(MemoryLane.RUNTIME_USER.name())
                .tenantId("default")
                .totalItems(0)
                .truncatedCount(0)
                .projectMemory(List.of())
                .userMemory(List.of())
                .pageContext(List.of())
                .workflowContext(List.of())
                .apiContext(List.of())
                .rules(List.of())
                .evidenceSummary(List.of())
                .build());

        RuntimeContextIdentity identity = baseIdentity("default", "user-a");
        identity.setRuntimePlacement("CENTRAL");
        RuntimeContextInjectionResult result = service.inject(identity);

        assertFalse(result.isEnabled());
        assertEquals("empty-package", result.getSkippedReason());
    }

    @Test
    void buildIdentityFromEmbedMetadataUsesGlobalUserIdForQueryUserId() {
        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setSessionId("sess-1");
        session.setTenantId("default");
        session.setProjectCode("demo-project");
        session.setExternalUserId("external-a");
        session.setGlobalUserId("global-a");
        session.setPageInstanceId("page-1");

        EmbedTokenClaims claims = new EmbedTokenClaims();
        claims.setTenantId("default");
        claims.setExternalUserId("external-a");
        claims.setGlobalUserId("global-a");
        claims.setRoles(List.of("USER"));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tenantId", "default");
        metadata.put("projectCode", "demo-project");
        metadata.put("externalUserId", "external-a");
        metadata.put("globalUserId", "global-a");
        metadata.put("pageInstanceId", "page-1");

        RuntimeContextIdentity identity = service.buildIdentity(session, claims, metadata);
        assertEquals("global-a", service.resolveUserId(identity));
        assertEquals("page-1", identity.getPageInstanceId());
    }

    @Test
    void injectForEmbedAgentUsesProfilePlacement() {
        when(composerService.compose(any())).thenReturn(samplePackage("user-a"));

        EmbedSessionEntity session = new EmbedSessionEntity();
        session.setSessionId("sess-1");
        session.setTenantId("default");
        session.setProjectCode("demo-project");
        session.setExternalUserId("user-a");
        session.setGlobalUserId("user-a");

        AgentEntryEntity agent = new AgentEntryEntity();
        agent.setId("agent-1");
        agent.setKeySlug("demo-agent");

        AgentRuntimeProfile profile = AgentRuntimeProfile.builder()
                .runtimePlacement("CENTRAL")
                .projectCode("demo-project")
                .build();

        RuntimeContextInjectionResult result = service.injectForEmbedAgent(
                session, null, Map.of("tenantId", "default", "projectCode", "demo-project"),
                agent, profile);

        assertTrue(result.isEnabled());
        ArgumentCaptor<ContextPackageComposeRequest> captor = ArgumentCaptor.forClass(ContextPackageComposeRequest.class);
        verify(composerService).compose(captor.capture());
        assertEquals("agent-1", captor.getValue().getQuery().getAgentId());
    }

    @Test
    void hybridPlacementDefersComposeUntilCentralFallback() {
        RuntimeContextIdentity identity = baseIdentity("default", "user-a");
        identity.setRuntimePlacement("HYBRID");

        RuntimeContextInjectionResult result = service.inject(identity);

        assertFalse(result.isEnabled());
        assertEquals("hybrid-placement-deferred", result.getSkippedReason());
        verify(composerService, never()).compose(any());
    }

    @Test
    void centralFallbackForHybridPlacementComposesOnce() {
        when(composerService.compose(any())).thenReturn(samplePackage("user-a"));

        RuntimeContextIdentity identity = baseIdentity("default", "user-a");
        identity.setRuntimePlacement("HYBRID");
        RuntimeContextInjectionResult deferred = service.inject(identity);

        RuntimeContextInjectionResult result = service.injectForCentralFallback(deferred);

        assertTrue(result.isEnabled());
        assertNotNull(result.getPromptSection());
        ArgumentCaptor<ContextPackageComposeRequest> captor = ArgumentCaptor.forClass(ContextPackageComposeRequest.class);
        verify(composerService).compose(captor.capture());
        assertEquals(MemoryLane.RUNTIME_USER.name(), captor.getValue().getQuery().getMemoryLane());
        assertEquals("HYBRID", captor.getValue().getQuery().getRetrievalMode());
        assertEquals("CENTRAL", result.getIdentity().getRuntimePlacement());
    }

    private RuntimeContextIdentity baseIdentity(String tenantId, String userId) {
        return RuntimeContextIdentity.builder()
                .tenantId(tenantId)
                .projectCode("demo-project")
                .userId(userId)
                .globalUserId(userId)
                .sessionId("sess-1")
                .traceId("trace-1")
                .build();
    }

    private ContextPackageResponse samplePackage(String userId) {
        ContextItemResponse item = ContextItemResponse.builder()
                .id(1L)
                .itemType(ContextItemType.PREFERENCE.name())
                .memoryLane(MemoryLane.RUNTIME_USER.name())
                .title("Theme")
                .summary("prefers dark mode for " + userId)
                .sourceType("USER_CONFIRMED")
                .trustLevel("HIGH")
                .confidence(new BigDecimal("0.9000"))
                .build();
        return ContextPackageResponse.builder()
                .memoryLane(MemoryLane.RUNTIME_USER.name())
                .tenantId("default")
                .totalItems(1)
                .truncatedCount(0)
                .projectMemory(List.of())
                .userMemory(List.of(ContextSearchResult.builder().item(item).rankScore(1.0).hitReason("binding").build()))
                .pageContext(List.of())
                .workflowContext(List.of())
                .apiContext(List.of())
                .rules(List.of())
                .evidenceSummary(List.of())
                .build();
    }
}
