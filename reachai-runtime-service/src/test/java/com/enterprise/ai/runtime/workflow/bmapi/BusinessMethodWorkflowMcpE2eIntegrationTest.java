package com.enterprise.ai.runtime.workflow.bmapi;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleMapper;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.registry.CapabilityApplyRecordMapper;
import com.enterprise.ai.agent.registry.CapabilityDiffItemMapper;
import com.enterprise.ai.agent.registry.CapabilitySnapshotMapper;
import com.enterprise.ai.agent.registry.CapabilitySyncLogMapper;
import com.enterprise.ai.agent.registry.ProjectInstanceMapper;
import com.enterprise.ai.agent.registry.RegistryCredentialMapper;
import com.enterprise.ai.agent.registry.RegistryEnrollmentService;
import com.enterprise.ai.agent.registry.RegistryEnrollmentTokenMapper;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodCatalogInternalController;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetStore;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetMapper;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodRevisionMapper;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodCatalogService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAcceptanceMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiCatalogInternalController;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiCatalogService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiContractCanonicalizer;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryMemberMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryStateMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryStateService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingMapper;
import com.enterprise.ai.capability.catalog.scan.ControllerScanHttpApiIntakeService;
import com.enterprise.ai.capability.catalog.scan.OpenApiScanHttpApiIntakeService;
import com.enterprise.ai.capability.catalog.scan.CapabilityScanProjectBlockerService;
import com.enterprise.ai.capability.catalog.scan.CapabilityScanProjectCatalogController;
import com.enterprise.ai.capability.catalog.scan.CapabilityScanProjectCatalogService;
import com.enterprise.ai.capability.catalog.scan.CapabilityScannerClient;
import com.enterprise.ai.capability.catalog.tool.CapabilityToolCatalogService;
import com.enterprise.ai.capability.catalog.tool.CapabilityToolCatalogController;
import com.enterprise.ai.capability.internalauth.CapabilityInternalAuthFilter;
import com.enterprise.ai.capability.internalauth.CapabilityInternalAuthProperties;
import com.enterprise.ai.capability.internalauth.CapabilityInternalAuthVerifier;
import com.enterprise.ai.capability.internalauth.JdbcCapabilityInternalAuthNonceStore;
import com.enterprise.ai.capability.internal.CapabilityHttpToolInvoker;
import com.enterprise.ai.capability.internal.CapabilityInvocationApplicationService;
import com.enterprise.ai.capability.internal.CapabilityInvocationAssetResolver;
import com.enterprise.ai.capability.internal.CapabilityInvocationPolicyChain;
import com.enterprise.ai.capability.internal.CapabilityInvokerRegistry;
import com.enterprise.ai.capability.internal.CapabilityOutboundTransportPolicy;
import com.enterprise.ai.capability.internal.CapabilityProjectInternalController;
import com.enterprise.ai.capability.internal.CapabilityProjectLookupService;
import com.enterprise.ai.capability.internal.CapabilitySourceContractGuard;
import com.enterprise.ai.capability.internal.CapabilityToolExecutionService;
import com.enterprise.ai.capability.internal.CapabilityToolInternalController;
import com.enterprise.ai.capability.internal.CapabilityToolLookupService;
import com.enterprise.ai.capability.internal.CatalogHttpCapabilityInvoker;
import com.enterprise.ai.capability.internal.DefaultCapabilityHttpToolInvoker;
import com.enterprise.ai.capability.registry.CapabilityCatalogProjectionStore;
import com.enterprise.ai.capability.registry.CapabilityChangeLifecycle;
import com.enterprise.ai.capability.registry.CapabilityChangePolicy;
import com.enterprise.ai.capability.registry.CapabilityRegistryCompatibilityController;
import com.enterprise.ai.capability.registry.CapabilityRegistryOperationsCompatibilityController;
import com.enterprise.ai.capability.registry.CapabilityRegistryService;
import com.enterprise.ai.capability.registry.CapabilityReviewEvidenceStore;
import com.enterprise.ai.capability.registry.CapabilitySourceIntakeService;
import com.enterprise.ai.capability.registry.StarterMvcHttpApiIntakeService;
import com.enterprise.ai.capability.registry.CapabilitySourceStateMapper;
import com.enterprise.ai.capability.registry.CapabilitySyncReceiptMapper;
import com.enterprise.ai.capability.registry.RegistryInstanceLifecycleService;
import com.enterprise.ai.capability.registry.RegistryProjectRegistrationService;
import com.enterprise.ai.control.capability.CapabilityCatalogConsoleController;
import com.enterprise.ai.control.capability.CapabilityChangeImpactService;
import com.enterprise.ai.control.capability.CapabilityReviewConsoleController;
import com.enterprise.ai.control.capability.CapabilityReviewGateway;
import com.enterprise.ai.control.capability.HttpApiReferenceController;
import com.enterprise.ai.control.capability.HttpApiConsoleController;
import com.enterprise.ai.control.capability.RuntimeHttpApiConsoleGateway;
import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.a2a.infrastructure.persistence.A2aPublicationMapper;
import com.enterprise.ai.control.a2a.infrastructure.persistence.A2aPublicationRevisionMapper;
import com.enterprise.ai.control.a2a.infrastructure.persistence.A2aPublishedReferenceReader;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.compat.CapabilityCompatibilityProxyController;
import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.governance.ControlToolAclEntity;
import com.enterprise.ai.control.governance.ControlToolAclMapper;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformAuthAuditEventMapper;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthProperties;
import com.enterprise.ai.control.identity.PlatformAuthProviderEntity;
import com.enterprise.ai.control.identity.PlatformAuthProviderMapper;
import com.enterprise.ai.control.identity.PlatformBearerAuthService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthAvailability;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformConsoleRoutePolicy;
import com.enterprise.ai.control.identity.PlatformIdentityController;
import com.enterprise.ai.control.identity.PlatformLoginSessionEntity;
import com.enterprise.ai.control.identity.PlatformLoginSessionMapper;
import com.enterprise.ai.control.identity.PlatformPermissionEntity;
import com.enterprise.ai.control.identity.PlatformPermissionMapper;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPasswordConfiguration;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.identity.PlatformRoleEntity;
import com.enterprise.ai.control.identity.PlatformRoleMapper;
import com.enterprise.ai.control.identity.PlatformRolePermissionEntity;
import com.enterprise.ai.control.identity.PlatformRolePermissionMapper;
import com.enterprise.ai.control.identity.PlatformSessionCookieService;
import com.enterprise.ai.control.identity.PlatformSessionTokenCodec;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.identity.PlatformUserMapper;
import com.enterprise.ai.control.identity.PlatformUserRoleEntity;
import com.enterprise.ai.control.identity.PlatformUserRoleMapper;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.control.runtime.ControlRuntimePublicController;
import com.enterprise.ai.control.runtime.ControlWorkflowReadOnlyTrialController;
import com.enterprise.ai.control.runtime.ControlWorkflowReadOnlyTrialGateway;
import com.enterprise.ai.control.runtime.RuntimeWorkflowReleaseGateway;
import com.enterprise.ai.control.mcp.api.protocol.McpAuditPayloadSanitizer;
import com.enterprise.ai.control.mcp.api.protocol.McpEndpointController;
import com.enterprise.ai.control.mcp.api.protocol.McpProtocolExceptionHandler;
import com.enterprise.ai.control.mcp.api.protocol.McpProtocolRequestGuard;
import com.enterprise.ai.control.mcp.api.management.McpPublicationController;
import com.enterprise.ai.control.mcp.api.management.McpClientController;
import com.enterprise.ai.control.mcp.api.management.McpHubManagementAccess;
import com.enterprise.ai.control.mcp.api.management.McpHubManagementExceptionHandler;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import com.enterprise.ai.control.mcp.application.CompositeMcpItemContractResolver;
import com.enterprise.ai.control.mcp.application.identity.McpClientApplicationService;
import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService;
import com.enterprise.ai.control.mcp.application.publication.McpPrecheckService;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.application.publication.McpPublicationApplicationService;
import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import com.enterprise.ai.control.mcp.infrastructure.persistence.McpCallLogMapper;
import com.enterprise.ai.control.mcp.infrastructure.persistence.McpClientMapper;
import com.enterprise.ai.control.mcp.infrastructure.persistence.McpPublicationItemMapper;
import com.enterprise.ai.control.mcp.infrastructure.persistence.McpPublicationMapper;
import com.enterprise.ai.control.mcp.infrastructure.persistence.McpPublicationRevisionMapper;
import com.enterprise.ai.control.mcp.infrastructure.persistence.McpPublishedReferenceReader;
import com.enterprise.ai.control.mcp.infrastructure.persistence.MybatisMcpCallAuditRepository;
import com.enterprise.ai.control.mcp.infrastructure.persistence.MybatisMcpClientRepository;
import com.enterprise.ai.control.mcp.infrastructure.persistence.MybatisMcpPublicationRepository;
import com.enterprise.ai.control.mcp.infrastructure.runtime.TrustedMcpRuntimeExecutionGateway;
import com.enterprise.ai.control.mcp.infrastructure.runtime.WorkflowItemContractResolver;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import com.enterprise.ai.reach.sdk.annotation.ReachSideEffectLevel;
import com.enterprise.ai.reach.spring.ReachAiRegistryClient;
import com.enterprise.ai.reach.spring.ReachAiRegistryProperties;
import com.enterprise.ai.reach.spring.ReachCapabilityBeanScanner;
import com.enterprise.ai.reach.spring.ReachCapabilityEndpoint;
import com.enterprise.ai.reach.spring.ReachCapabilityInvocationVerifier;
import com.enterprise.ai.reach.spring.ReachCapabilityInvoker;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogFeignClient;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityInternalAuthSigner;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowUsageReader;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpEgressPolicy;
import com.enterprise.ai.runtime.execution.capability.RuntimeCapabilityCatalogGateway;
import com.enterprise.ai.runtime.api.RuntimeWorkflowCredentialPublicController;
import com.enterprise.ai.runtime.api.RuntimePublicController;
import com.enterprise.ai.runtime.api.RuntimeWorkflowPublicController;
import com.enterprise.ai.runtime.api.RuntimeWorkflowRevisionExceptionHandler;
import com.enterprise.ai.runtime.api.RuntimeWorkflowVersionPublicController;
import com.enterprise.ai.runtime.internalauth.InternalServiceAuthFilter;
import com.enterprise.ai.runtime.internalauth.InternalServiceAuthProperties;
import com.enterprise.ai.runtime.internalauth.InternalServiceAuthVerifier;
import com.enterprise.ai.runtime.internalauth.JdbcInternalAuthNonceStore;
import com.enterprise.ai.runtime.internal.RuntimeCapabilityReferenceInternalController;
import com.enterprise.ai.runtime.internal.RuntimeCapabilityReferenceService;
import com.enterprise.ai.runtime.mcp.api.McpToolExecutionInternalController;
import com.enterprise.ai.runtime.mcp.application.RuntimeMcpToolExecutionService;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionLogMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsQueryService;
import com.enterprise.ai.runtime.runops.consolehttpapi.ControllerOrderFixtureController;
import com.enterprise.ai.runtime.runops.consolehttpapi.RuntimeHttpApiConnectionMapper;
import com.enterprise.ai.runtime.runops.consolehttpapi.RuntimeHttpApiConnectionService;
import com.enterprise.ai.runtime.runops.consolehttpapi.RuntimeHttpApiConnectionInternalController;
import com.enterprise.ai.runtime.runops.consolehttpapi.RuntimeHttpApiInvocationService;
import com.enterprise.ai.runtime.runops.consolehttpapi.RuntimeHttpApiInvocationInternalController;
import com.enterprise.ai.runtime.runops.consolehttpapi.RuntimeHttpApiCatalogStateService;
import com.enterprise.ai.runtime.runops.consolehttpapi.RuntimeHttpApiCatalogStateInternalController;
import com.enterprise.ai.runtime.runops.consolecapability.ConsoleCapabilityInvocationMapper;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService;
import com.enterprise.ai.runtime.trace.RuntimeTraceQueryService;
import com.enterprise.ai.runtime.workflow.RuntimeCapabilityContractPins;
import com.enterprise.ai.runtime.workflow.RuntimePublishedWorkflowSnapshotReader;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDeletionReferences;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDocumentCanonicalizer;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowManagementService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReferenceIndex;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReferenceMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseEventMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowStudioService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowHttpApiPinMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowHttpApiService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReadOnlyTrialService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReadOnlyTrialInternalController;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialCipher;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialMapper;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.enterprise.ai.text.tooling.scanner.controller.ControllerAnnotationToolManifestScanner;
import com.enterprise.ai.text.tooling.scanner.openapi.OpenApiToolManifestScanner;
import com.enterprise.ai.text.tooling.scanner.manifest.HttpApiOperation;
import com.enterprise.ai.text.tooling.scanner.manifest.ProjectMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BMAPI-2D-B's first isolated vertical slice.  The fixture deliberately uses
 * production controllers, signing and persistence.  Its loopback bridges only
 * adapt an actual HTTP socket to MockMvc; they do not add test success routes.
 */
class BusinessMethodWorkflowMcpE2eIntegrationTest {

    private static final String PROJECT_CODE = "bmapi2d";
    private static final String INTERNAL_SECRET = "bmapi-2d-isolated-internal-secret";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private Fixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new Fixture(json);
        fixture.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void starterRegistrationAcceptanceAndPublicBusinessMethodCatalogUseOneQualifiedName() throws Exception {
        fixture.registerAndSync();

        // The current owner policy automatically accepts complete READ_ONLY SDK declarations.
        // This is still an owner-side acceptance: the stored source state must be READY and
        // the review record must say AUTO_APPLIED rather than a caller-supplied catalog row.
        fixture.assertInitialReadOnlyDeclarationsWereAutoAccepted();
        List<Map<String, Object>> methods = records(fixture.publicBusinessMethods());
        assertEquals(3, methods.size());
        assertMethod(methods, "bmapi2d_health", "bmapi2d:health", 0);
        assertMethod(methods, "bmapi2d_normalizeOrderNo", "bmapi2d:normalizeOrderNo", 1);
        assertMethod(methods, "bmapi2d_queryOrder", "bmapi2d:queryOrder", 3);
        assertTrue(methods.stream().allMatch(method -> "BUSINESS_METHOD".equals(method.get("assetType"))));
        assertTrue(methods.stream().allMatch(method -> Boolean.TRUE.equals(method.get("enabled"))));
        assertTrue(methods.stream().allMatch(method -> "READY".equals(method.get("sourceAvailability"))));

        Map<String, Object> dto = methods.stream()
                .filter(method -> "bmapi2d_queryOrder".equals(method.get("name")))
                .findFirst().orElseThrow();
        assertEquals("bmapi2d:queryOrder", dto.get("qualifiedName"));
        assertEquals("READ_ONLY", dto.get("sideEffect"));
        assertTrue(String.valueOf(dto.get("responseType")).contains("OrderResult"));
        assertEquals(List.of("request", "request.customerId", "request.orderNo"),
                ((List<Map<String, Object>>) dto.get("parameters")).stream()
                        .map(parameter -> String.valueOf(parameter.get("name"))).toList());
        assertEquals(0, fixture.capabilityHits.get(), "registration only must not execute the SDK fixture");
    }

    @Test
    void runtimeToCapabilityToSdkExecutesZeroScalarAndDtoContractsWithoutShapeLoss() {
        fixture.registerAndSync();

        var result = fixture.runtimeGraphExecutor().execute(workflowGraph(), Map.of(
                "params", Map.of(
                        "orderNo", "A-1024",
                        "request", Map.of("orderNo", "A-1024", "customerId", "customer-7"))),
                com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink.NOOP,
                com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation.none(),
                com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity.fromAgent(
                        PROJECT_CODE, fixture.projectId(), PROJECT_CODE, null));

        assertTrue(result.success(), result.code() + ": " + result.answer()
                + "; SDK loopback=" + fixture.sdkBridge.lastResponseSummary());
        assertEquals(3, fixture.capabilityHits.get(), "all three SDK methods must be reached through Runtime and Capability");
        assertEquals("A-1024", fixture.invocationEvidence.scalarInput.get(),
                "params.orderNo must remain a scalar String at the SDK boundary");
        assertEquals(Map.of("orderNo", "A-1024", "customerId", "customer-7"),
                fixture.invocationEvidence.dtoInput.get(),
                "params.request must remain one nested DTO object at the SDK boundary");
        assertEquals(1, fixture.invocationEvidence.zeroArgumentCalls.get(),
                "config.args={} must invoke the SDK zero-argument method with no synthetic input argument");
        assertTrue(result.answer().contains("ORD-A-1024"), "final Workflow result must expose the SDK return orderId");
        assertTrue(result.answer().contains("OPEN"), "final Workflow result must expose the SDK return status");
    }

    @Test
    void publicStudioSaveReadValidateAndPublishPinsOwnerDefinitions() throws Exception {
        fixture.registerAndSync();

        Fixture.WorkflowRelease release = fixture.createSaveReadValidateAndPublish();

        assertNotNull(release.workflowId());
        assertTrue(release.versionId() > 0L);
        fixture.assertPublishedOwnerPins(release);
    }

    @Test
    void publicBusinessMethodReferencesExposeActualDraftAndPublishedWorkflowUsage() throws Exception {
        fixture.registerAndSync();

        Fixture.WorkflowRelease release = fixture.createSaveReadValidateAndPublish();
        fixture.publishMcpWorkflow(release);

        Map<String, Object> evidence = fixture.publicBusinessMethodReferences("bmapi2d_queryOrder");
        assertEquals("COMPLETE", evidence.get("runtimeEvidence"));
        assertEquals("COMPLETE", evidence.get("publicationEvidence"));
        List<Map<String, Object>> references = json.convertValue(evidence.get("references"), new TypeReference<>() { });
        assertTrue(references.stream().anyMatch(reference -> "WORKFLOW".equals(reference.get("kind"))
                        && release.workflowId().equals(reference.get("id"))
                        && "DRAFT".equals(reference.get("stage"))),
                "the owner reference index must expose the saved working copy");
        assertTrue(references.stream().anyMatch(reference -> "WORKFLOW".equals(reference.get("kind"))
                        && release.workflowId().equals(reference.get("id"))
                        && "PUBLISHED".equals(reference.get("stage"))
                        && String.valueOf(release.versionId()).equals(String.valueOf(reference.get("versionId")))),
                "the owner reference index must expose the frozen published version");
        assertTrue(references.stream().anyMatch(reference -> "MCP".equals(reference.get("kind"))
                        && "PUBLISHED".equals(reference.get("stage"))),
                "the public impact response must join the reachable MCP publication evidence");
    }

    @Test
    void publishedMcpWorkflowUsesTheFrozenReleaseAndReachesAllBusinessMethods() throws Exception {
        fixture.registerAndSync();

        Fixture.WorkflowRelease release = fixture.createSaveReadValidateAndPublish();
        Fixture.McpInvocation invocation = fixture.callPublishedMcpWorkflow(release);

        assertTrue(invocation.answer().contains("ORD-A-1024"));
        assertTrue(invocation.answer().contains("OPEN"));
        assertNotNull(invocation.runId());
        assertNotNull(invocation.traceId());
        assertEquals(3, fixture.capabilityHits.get(),
                "one MCP tools/call must traverse each published Workflow TOOL exactly once");
        fixture.assertMcpTrace(invocation);
    }

    @Test
    void mcpWorkflowRejectsPendingDriftAcceptedUnrepublishedAndRemovedSourcesWithoutSdkCalls() throws Exception {
        fixture.registerAndSync();

        Fixture.WorkflowRelease release = fixture.createSaveReadValidateAndPublish();
        Fixture.McpSurface surface = fixture.publishMcpWorkflow(release);
        fixture.callMcpWorkflow(surface);
        int baselineHits = fixture.capabilityHits.get();
        assertEquals(3, baselineHits);

        fixture.syncSource(DriftBusinessFixture.class, DriftBusinessFixture::new);
        fixture.assertMcpRejected(surface, "CAPABILITY_CONTRACT_DRIFT", baselineHits);

        fixture.acceptLatestPendingSourceChanges();
        fixture.assertMcpRejected(surface, "CAPABILITY_PUBLISHED_CONTRACT_CHANGED", baselineHits);

        fixture.syncSource(RemovedHealthBusinessFixture.class, RemovedHealthBusinessFixture::new);
        fixture.assertMcpRejected(surface, "CAPABILITY_SOURCE_MISSING", baselineHits);
    }

    @Test
    void credentialRevisionRejectsOldConsoleConfirmationAndPublishedMcpWithoutSdkRequests() throws Exception {
        fixture.registerAndSync();
        fixture.grantTrialPermissions();
        fixture.grantMethodTrialAcl();
        String methodPath = "/api/business-methods/bmapi2d_normalizeOrderNo";
        var context = fixture.multiSourcePublicRequest("GET", methodPath + "/invocation-context", null);
        assertEquals(200, context.statusCode());
        var old = fixture.responseMap(context);
        Fixture.WorkflowRelease release = fixture.createSaveReadValidateAndPublish();
        Fixture.McpSurface surface = fixture.publishMcpWorkflow(release);
        Long credentialId = fixture.database.jdbc().queryForObject(
                "SELECT id FROM capability_registry_project_credential WHERE project_code=?", Long.class, PROJECT_CODE);
        fixture.registrySecurity.updateAdministrativePolicy(credentialId,
                List.of("https://orders.test"), List.of(), 600, "ACTIVE");
        fixture.assertMcpRejected(surface, "BUSINESS_METHOD_EXECUTION_BINDING_CHANGED", 0);

        var denied = fixture.multiSourcePublicRequest("POST", methodPath + "/invocations", Map.of(
                "invocationId", UUID.randomUUID().toString(), "expectedContractHash", old.get("currentContractHash"),
                "expectedExecutionRevision", old.get("executionRevision"),
                "input", Map.of("orderNo", "BINDING-6"), "confirmedSideEffect", false));
        assertEquals(409, denied.statusCode());
        assertEquals("BUSINESS_METHOD_EXECUTION_BINDING_CHANGED", fixture.responseMap(denied).get("code"));
        assertEquals(0, fixture.capabilityHits.get());
        assertEquals(0, fixture.sdkBridge.requestCount());
        var fresh = fixture.responseMap(fixture.multiSourcePublicRequest("GET", methodPath + "/invocation-context", null));
        assertEquals(old.get("currentContractHash"), fresh.get("currentContractHash"));
        assertNotEquals(old.get("executionRevision"), fresh.get("executionRevision"));
        var called = fixture.multiSourcePublicRequest("POST", methodPath + "/invocations", Map.of(
                "invocationId", UUID.randomUUID().toString(), "expectedContractHash", fresh.get("currentContractHash"),
                "expectedExecutionRevision", fresh.get("executionRevision"),
                "input", Map.of("orderNo", "BINDING-6"), "confirmedSideEffect", false));
        assertEquals(200, called.statusCode(), new String(called.body(), StandardCharsets.UTF_8));
        assertEquals("SUCCEEDED", fixture.responseMap(called).get("status"));
        assertEquals(1, fixture.capabilityHits.get());
        assertEquals(1, fixture.sdkBridge.requestCount());
    }

    @Test
    void ordinaryMethodStudioDebugCannotBypassSignedReadOnlyTrial() throws Exception {
        fixture.registerAndSync();
        Fixture.WorkflowRelease release = fixture.createSaveReadValidateAndPublish();
        Fixture.DebugExecution debug = fixture.runStudioDebug(release);
        assertFalse(debug.success(), "ordinary Studio DEBUG_UNTRUSTED must not execute a business method");
        assertEquals(0, fixture.capabilityHits.get(), "ordinary debug must reject before the SDK endpoint");
        assertEquals(0, fixture.sdkBridge.requestCount(), "zero SDK HTTP requests, not just zero method results");
    }

    @Test
    void ordinaryMethodGraphCannotForgeProjectIdentityWithBusinessInput() throws Exception {
        fixture.registerAndSync();
        var result = fixture.runtimeGraphExecutor().execute(workflowGraph(), Map.of(
                "tenantId", PROJECT_CODE, "projectCode", PROJECT_CODE, "projectId", fixture.projectId(),
                "identity", Map.of("source", "STUDIO_PROJECT_TEST", "projectTrusted", true),
                "params", Map.of("orderNo", "A-1024", "request", Map.of("orderNo", "A-1024", "customerId", "customer-7"))));
        assertFalse(result.success());
        assertTrue(result.answer().contains("BUSINESS_METHOD_DEBUG_IDENTITY_DENIED"), result.answer());
        assertEquals(0, fixture.capabilityHits.get()); assertEquals(0, fixture.sdkBridge.requestCount());
    }

    @Test
    void controllerApiBrowserGraphPublishesAndRunsViaSignedMcpWorkflow() throws Exception {
        fixture.prepareAcceptedControllerApi();
        Fixture.WorkflowRelease release = fixture.createSaveReadValidateAndPublishControllerApi();
        Fixture.McpSurface surface = fixture.publishMcpWorkflow(release, "orders", 41L,
                "bmapi-3c-api-workflow");
        Fixture.McpInvocation invocation = fixture.callMcpWorkflow(surface,
                Map.of("orderId", "O-321", "detailLevel", "full"));

        assertTrue(invocation.answer().contains("PAID"));
        assertTrue(invocation.answer().contains("order_state"), invocation.answer());
        assertFalse(invocation.answer().contains("synthetic-controller-value"));
        assertEquals(1, fixture.controllerOrder.methodCalls());
        assertNotNull(invocation.runId());
        assertNotNull(invocation.traceId());
        fixture.assertControllerApiReleaseAndTrace(release, invocation);
    }

    @Test
    void normalSourceRowsReconcileReadOnlyWithoutLegacyToolBindings() throws Exception {
        fixture.prepareMultiSourceBrowserSurface();
        assertEquals(200, fixture.multiSourcePublicRequest("POST", "/api/scan-projects/41/rescan", Map.of()).statusCode());
        fixture.selectMultiSourceOpenApi();
        assertEquals(200, fixture.multiSourcePublicRequest("POST", "/api/scan-projects/41/rescan", Map.of()).statusCode());
        var sourceRows = fixture.database.jdbc().queryForList("SELECT * FROM capability_scan_project_tool ORDER BY id");
        var projections = fixture.database.jdbc().queryForList("SELECT * FROM capability_tool_definition ORDER BY id");
        var api = fixture.httpApiCatalog.detail(1L);
        int upstreamCalls = fixture.controllerOrderBridge.requestCount();
        assertEquals(2, sourceRows.size());
        assertTrue(sourceRows.stream().allMatch(row -> row.get("global_tool_definition_id") == null));

        var response = fixture.multiSourcePublicRequest("POST", "/api/scan-projects/41/tools/reconcile", Map.of());

        assertEquals(200, response.statusCode(), fixture.controlBridge.lastResponseSummary());
        var summary = fixture.json.readTree(response.body());
        assertEquals(2, summary.path("notLinked").asInt());
        assertEquals(0, summary.path("sdkMirrorsEnsured").asInt());
        assertEquals(0, summary.path("sdkReviewPendingRows").asInt());
        assertEquals(sourceRows, fixture.database.jdbc().queryForList("SELECT * FROM capability_scan_project_tool ORDER BY id"));
        assertEquals(projections, fixture.database.jdbc().queryForList("SELECT * FROM capability_tool_definition ORDER BY id"));
        assertEquals(api, fixture.httpApiCatalog.detail(1L));
        assertEquals(upstreamCalls, fixture.controllerOrderBridge.requestCount());
        assertEquals(0, fixture.database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_console_capability_invocation", Integer.class));
    }

    @Test
    void normalMultiSourceScansConsoleGetAndConflictBlockNewCallsAndPublication() throws Exception {
        fixture.prepareMultiSourceBrowserSurface();
        for (String path : List.of("modules", "semantic/status", "sensitive-data/status")) {
            assertEquals(200, fixture.multiSourcePublicRequest("GET", "/api/scan-projects/41/" + path, null).statusCode(),
                    "the normal scanning page must read its real auxiliary owner services: " + path);
        }
        assertEquals(200, fixture.multiSourcePublicRequest("POST", "/api/scan-projects/41/rescan", Map.of()).statusCode());
        fixture.selectMultiSourceOpenApi();
        assertEquals(200, fixture.multiSourcePublicRequest("POST", "/api/scan-projects/41/rescan", Map.of()).statusCode());
        var owner = fixture.httpApiCatalog.detail(1L);
        assertTrue(owner.summary().sourceConfirmed());
        assertEquals(2, owner.summary().activeSourceCount());
        assertEquals(1, fixture.database.jdbc().queryForObject("SELECT COUNT(*) FROM capability_http_api_asset", Integer.class));
        assertEquals(2, fixture.database.jdbc().queryForObject("SELECT COUNT(*) FROM capability_http_api_source_binding", Integer.class));
        assertEquals(0, fixture.database.jdbc().queryForObject("SELECT COUNT(*) FROM control_tool_acl WHERE project_code='orders'", Integer.class),
                "equivalent discovery does not grant invocation authorization");
        assertEquals(200, fixture.multiSourcePublicRequest("POST", "/api/apis/1/accept",
                Map.of("expectedSourceSetRevision", owner.summary().sourceSetRevision())).statusCode());
        fixture.grantApiTrialAcl();
        owner = fixture.httpApiCatalog.detail(1L);
        String accepted = owner.summary().acceptedContractHash();
        assertEquals(200, fixture.saveMultiSourceConnection().statusCode());
        var body = fixture.multiSourceInvocationBody(accepted);
        var called = fixture.multiSourcePublicRequest("POST", "/api/apis/1/invocations", body);
        assertEquals(200, called.statusCode(), new String(called.body(), StandardCharsets.UTF_8));
        var result = fixture.json.readTree(called.body());
        assertEquals("SUCCEEDED", result.path("status").asText());
        assertEquals(1, fixture.controllerOrder.methodCalls());
        assertEquals(1, fixture.controllerOrderBridge.requestCount());
        assertEquals(200, fixture.multiSourcePublicRequest("GET", "/api/runops/traces/" + result.path("traceId").asText(), null).statusCode());
        Fixture.WorkflowRelease published = fixture.createSaveReadValidateAndPublishControllerApi();
        String beforeSnapshot = fixture.workflowVersions.selectById(published.versionId()).getGraphSpecSnapshotJson();

        fixture.changeMultiSourceSpec("conflict");
        assertEquals(200, fixture.multiSourcePublicRequest("POST", "/api/scan-projects/41/rescan", Map.of()).statusCode());
        assertEquals("CONFLICT", fixture.httpApiCatalog.detail(1L).summary().sourceStatus());
        assertEquals(accepted, fixture.httpApiCatalog.detail(1L).summary().acceptedContractHash());
        var denied = fixture.multiSourcePublicRequest("POST", "/api/apis/1/invocations", fixture.multiSourceInvocationBody(accepted));
        assertEquals(409, denied.statusCode());
        String revision = Fixture.requiredText(fixture.responseMap(fixture.runtimeRequest("GET",
                "/api/workflows/" + published.workflowId() + "/working-copy", null, Map.of())), "revision");
        var publish = fixture.multiSourcePublicRequest("POST", "/api/workflows/" + published.workflowId() + "/versions/publish",
                Map.of("version", "1.0.1", "rolloutPercent", 100, "note", "conflicting source must be rejected", "baseRevision", revision));
        assertEquals(400, publish.statusCode(), "new release must be rejected at owner validation");
        assertEquals("HTTP_API_SOURCE_NOT_READY", fixture.json.readTree(publish.body()).path("message").asText());
        assertEquals(beforeSnapshot, fixture.workflowVersions.selectById(published.versionId()).getGraphSpecSnapshotJson());
        assertEquals(1, fixture.controllerOrder.methodCalls());
        assertEquals(1, fixture.controllerOrderBridge.requestCount());
        assertEquals(1, fixture.database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_console_capability_invocation", Integer.class));
    }

    @Test
    void retiredPublicAndDirectEntriesCannotWriteDispatchOrChangeHistoricalDraft() throws Exception {
        fixture.prepareRetirementBrowserSurface();
        assertEquals(200, fixture.changeScan("controller").statusCode());
        Long scanId = fixture.database.jdbc().queryForObject(
                "SELECT id FROM capability_scan_project_tool WHERE project_id=41 ORDER BY id LIMIT 1", Long.class);
        // This is deliberately historical data, not a shortcut for normal API discovery/acceptance.
        var legacy = new com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity();
        legacy.setName("orders_legacy_query"); legacy.setQualifiedName("orders:legacy-query");
        legacy.setTitle("Historical manual scan projection"); legacy.setDescription("Historical fixture only");
        legacy.setProjectId(41L); legacy.setProjectCode("orders"); legacy.setAssetType("UNCLASSIFIED");
        legacy.setSource("scanner"); legacy.setSourceLocation("ControllerOrderFixtureController.java");
        legacy.setParametersJson("[]"); legacy.setEnabled(true); legacy.setSideEffect("READ_ONLY");
        legacy.setHttpMethod("GET"); legacy.setBaseUrl(fixture.controllerOrderBridge.baseUrl());
        legacy.setEndpointPath("/orders/O-5A"); legacy.setResponseType("JSON");
        fixture.sessionUnchecked().getMapper(ToolDefinitionMapper.class).insert(legacy);
        fixture.database.jdbc().update("UPDATE capability_scan_project_tool SET global_tool_definition_id=? WHERE id=?",
                legacy.getId(), scanId);
        var beforeDefinitions = fixture.database.jdbc().queryForList("SELECT * FROM capability_tool_definition ORDER BY id");
        var beforeSources = fixture.database.jdbc().queryForList("SELECT * FROM capability_scan_project_tool ORDER BY id");
        String rowPath = "/api/scan-projects/41/scan-tools/" + scanId;
        List<String[]> routes = List.of(new String[]{"PUT", rowPath}, new String[]{"PUT", rowPath + "/toggle"},
                new String[]{"POST", rowPath + "/test"}, new String[]{"POST", rowPath + "/promote-to-tool"},
                new String[]{"POST", rowPath + "/push-to-global-tool"}, new String[]{"POST", rowPath + "/unpromote-from-global"},
                new String[]{"POST", "/api/scan-projects/41/scan-tools/promote-by-module"});
        for (var route : routes) {
            var publicResult = fixture.multiSourcePublicRequest(route[0], route[1], Map.of("enabled", false));
            int retiredStatus = route[1].equals(rowPath) || route[1].endsWith("/promote-by-module") ? 405 : 404;
            assertEquals(retiredStatus, publicResult.statusCode(), route[1] + ": " + new String(publicResult.body(), StandardCharsets.UTF_8));
            var direct = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(fixture.capabilityBridge.baseUrl() + route[1]))
                    .header("Content-Type", "application/json").method(route[0], HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(retiredStatus, direct.statusCode(), route[1]);
        }
        assertEquals(404, fixture.multiSourcePublicRequest("POST",
                "/api/scan-projects/41/scan-tools/999999/promote-to-tool", Map.of()).statusCode());
        var cross = fixture.multiSourcePublicRequest("POST", "/api/scan-projects/" + fixture.projectId()
                + "/scan-tools/" + scanId + "/test", Map.of());
        assertEquals(404, cross.statusCode()); assertEquals(0, cross.body().length);
        assertEquals(200, fixture.multiSourcePublicRequest("POST", "/api/scan-projects/41/tools/reconcile", Map.of()).statusCode());
        var generic = fixture.multiSourcePublicRequest("GET", "/api/tools/orders_legacy_query", null);
        assertEquals(404, generic.statusCode(), "a raw projection without an owner is not a readable method");
        var gateway = new RuntimeCapabilityCatalogGateway(new LoopbackRuntimeCapabilityTransport(
                fixture.capabilityBridge.baseUrl(), json), new RuntimeCapabilityInternalAuthSigner(INTERNAL_SECRET), json);
        var denied = gateway.invokeTool("orders:legacy-query", Map.of("input", Map.of()));
        assertEquals("CAPABILITY_TOOL_NOT_FOUND", denied.code()); assertFalse(denied.success());
        String oldGraph = "{\"schemaVersion\":2,\"nodes\":[{\"id\":\"old\",\"type\":\"TOOL\",\"ref\":{\"kind\":\"TOOL\","
                + "\"qualifiedName\":\"orders:legacy-query\"},\"config\":{}}],\"edges\":[],\"entryNodeId\":\"old\",\"exitNodeIds\":[\"old\"]}";
        var oldExecution = fixture.runtimeGraphExecutor().execute(oldGraph,
                Map.of(), com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink.NOOP,
                com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation.none(),
                com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity.fromAgent("orders", 41L, "orders", null));
        assertFalse(oldExecution.success());
        assertTrue((oldExecution.code() + " " + oldExecution.answer()).contains("CAPABILITY_TOOL_NOT_FOUND"));
        var working = fixture.changeWorkingCopy();
        Map<String, Object> save = new LinkedHashMap<>();
        for (String key : List.of("name", "keySlug", "description", "workflowKind", "executionEngine",
                "inputSchemaJson", "outputSchemaJson", "extraJson", "canvasJson")) save.put(key, working.get(key));
        save.put("graphSpecJson", oldGraph); save.put("baseRevision", working.get("revision"));
        var savedHistorical = fixture.multiSourcePublicRequest("PUT", "/api/workflows/" + fixture.multiSourceWorkflowId + "/working-copy", save);
        assertEquals(200, savedHistorical.statusCode(), fixture.runtimeBridge.lastResponseSummary());
        var oldDraft = fixture.changeWorkingCopy();
        var validation = fixture.multiSourcePublicRequest("POST", "/api/workflows/" + fixture.multiSourceWorkflowId + "/versions/validate", Map.of());
        assertEquals(200, validation.statusCode()); // Structural validation is separate from publication owner pinning.
        var publish = fixture.multiSourcePublicRequest("POST", "/api/workflows/" + fixture.multiSourceWorkflowId + "/versions/publish",
                Map.of("version", "1.0.0", "baseRevision", oldDraft.get("revision")));
        assertEquals(400, publish.statusCode());
        assertTrue(new String(publish.body(), StandardCharsets.UTF_8).contains("能力来源或契约尚未就绪"));
        assertEquals(oldDraft.get("graphSpecJson"), fixture.changeWorkingCopy().get("graphSpecJson"));
        assertEquals(beforeDefinitions, fixture.database.jdbc().queryForList("SELECT * FROM capability_tool_definition ORDER BY id"));
        assertEquals(beforeSources, fixture.database.jdbc().queryForList("SELECT * FROM capability_scan_project_tool ORDER BY id"));
        assertEquals(0, fixture.controllerOrderBridge.requestCount()); assertEquals(0, fixture.capabilityHits.get());
        assertEquals(0, fixture.database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_console_capability_invocation", Integer.class));
        assertEquals(0, fixture.database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_version", Integer.class));
        assertEquals(0, fixture.database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_http_api_pin", Integer.class));
    }

    @Test
    void normalStarterQueryConsoleSucceedsWithoutManualScanPromotion() throws Exception {
        fixture.prepareRetirementBrowserSurface();
        fixture.assertInitialReadOnlyDeclarationsWereAutoAccepted();
        assertEquals(3, records(fixture.publicBusinessMethods()).size());
        var context = fixture.multiSourcePublicRequest("GET", "/api/business-methods/bmapi2d_queryOrder/invocation-context", null);
        assertEquals(200, context.statusCode(), new String(context.body(), StandardCharsets.UTF_8));
        var owner = fixture.responseMap(context);
        var called = fixture.multiSourcePublicRequest("POST", "/api/business-methods/bmapi2d_queryOrder/invocations",
                Map.of("invocationId", UUID.randomUUID().toString(), "expectedContractHash", owner.get("currentContractHash"),
                        "expectedExecutionRevision", owner.get("executionRevision"),
                        "input", Map.of("request", Map.of("orderNo", "A-5A", "customerId", "customer-5A")), "confirmedSideEffect", false));
        assertEquals(200, called.statusCode(), new String(called.body(), StandardCharsets.UTF_8));
        assertEquals("SUCCEEDED", fixture.responseMap(called).get("status"));
        assertEquals(1, fixture.capabilityHits.get());
        assertEquals(Map.of("orderNo", "A-5A", "customerId", "customer-5A"), fixture.invocationEvidence.dtoInput.get());
        assertEquals(3, fixture.database.jdbc().queryForObject("SELECT COUNT(*) FROM capability_tool_definition", Integer.class));
        assertEquals(0, fixture.retiredEntryRequestCount());
    }

    @Test
    void controllerApiStudioDebugCannotUseATrustedPublishedIdentity() throws Exception {
        fixture.prepareAcceptedControllerApi();
        Fixture.ControllerApiDraft draft = fixture.createSavedControllerApiDraft();
        Map<String, Object> request = Map.of("workflowId", draft.workflowId(),
                "message", "isolated API Studio debug",
                "inputParams", Map.of("orderId", "O-321", "detailLevel", "full"),
                "debugOptions", Map.of());
        HttpResponse<byte[]> response = fixture.runtimeRequest("POST", "/api/workflows/studio/debug-run",
                fixture.json.writeValueAsBytes(request), Map.of());
        assertEquals(200, response.statusCode(), fixture.runtimeBridge.lastResponseSummary());
        Map<String, Object> result = fixture.responseMap(response);
        assertEquals(Boolean.FALSE, result.get("success"));
        assertTrue(String.valueOf(result.get("errorCode")).startsWith("HTTP_API_"),
                "Studio debug must fail at the API trust boundary: " + result.get("errorCode"));
        assertEquals(0, fixture.controllerOrder.methodCalls());
    }

    @Test
    void controllerApiMcpTraceRendersThroughRunOpsPublicPath() throws Exception {
        fixture.prepareAcceptedControllerApi();
        Fixture.WorkflowRelease release = fixture.createSaveReadValidateAndPublishControllerApi();
        Fixture.McpInvocation invocation = fixture.callMcpWorkflow(
                fixture.publishMcpWorkflow(release, "orders", 41L, "bmapi-3c-api-workflow"),
                Map.of("orderId", "O-321", "detailLevel", "full"));
        var detail = fixture.runOpsQuery.detail(invocation.traceId());
        assertEquals("orders", detail.summary().projectCode());
        assertTrue(detail.spans().stream().anyMatch(span -> "api-node".equals(span.nodeId())));
        fixture.json.writeValueAsString(detail);
        HttpResponse<byte[]> runtime = fixture.runtimeRequest("GET",
                "/api/runops/traces/" + invocation.traceId(), null, Map.of());
        assertEquals(200, runtime.statusCode(), fixture.runtimeBridge.lastResponseSummary());
        fixture.assertPublicRunOps(invocation);
    }

    @Test
    void controllerApiPublishedReleaseRejectsOwnerDriftAndOuterMcpAclBeforeDispatch() throws Exception {
        fixture.prepareAcceptedControllerApi();
        Fixture.WorkflowRelease release = fixture.createSaveReadValidateAndPublishControllerApi();
        Fixture.McpSurface surface = fixture.publishMcpWorkflow(release, "orders", 41L,
                "bmapi-3c-api-workflow");
        Map<String, Object> input = Map.of("orderId", "O-321", "detailLevel", "full");
        String originalSourceStatus = fixture.database.jdbc().queryForObject(
                "SELECT status FROM capability_http_api_source_binding WHERE asset_id = 1 LIMIT 1", String.class);
        fixture.database.jdbc().update("UPDATE capability_http_api_source_binding "
                + "SET source_contract_hash = ? WHERE asset_id = 1", "b".repeat(64));
        assertEquals("HTTP_API_SOURCE_NOT_READY", fixture.callMcpWorkflowError(surface, input));
        assertEquals(0, fixture.controllerOrder.methodCalls());
        fixture.database.jdbc().update("UPDATE capability_http_api_source_binding "
                + "SET source_contract_hash = ? WHERE asset_id = 1",
                fixture.httpApiCatalog.detail(1L).summary().acceptedContractHash());

        fixture.database.jdbc().update("UPDATE runtime_http_api_connection "
                + "SET revision = revision + 1 WHERE qualified_name = ?",
                fixture.httpApiCatalog.detail(1L).summary().qualifiedName());
        assertEquals("HTTP_API_PUBLISHED_PIN_STALE", fixture.callMcpWorkflowError(surface, input));
        assertEquals(0, fixture.controllerOrder.methodCalls());
        fixture.database.jdbc().update("UPDATE runtime_http_api_connection "
                + "SET revision = revision - 1 WHERE qualified_name = ?",
                fixture.httpApiCatalog.detail(1L).summary().qualifiedName());

        fixture.database.jdbc().update("UPDATE capability_http_api_source_binding "
                + "SET status = 'REMOVED', removed_at = CURRENT_TIMESTAMP WHERE asset_id = 1");
        assertEquals("HTTP_API_SOURCE_NOT_READY", fixture.callMcpWorkflowError(surface, input));
        assertEquals(0, fixture.controllerOrder.methodCalls());
        fixture.database.jdbc().update("UPDATE capability_http_api_source_binding "
                + "SET status = ?, removed_at = NULL WHERE asset_id = 1", originalSourceStatus);
        assertEquals("ACCEPTED", fixture.httpApiCatalog.detail(1L).summary().sourceStatus());
        // This is the outer MCP Workflow publication ACL, not a per-API-node ACL.
        fixture.database.jdbc().update("UPDATE control_tool_acl SET enabled = 0 WHERE project_code = 'orders'");
        fixture.assertMcpPolicyDenied(surface, input);
        assertEquals(0, fixture.controllerOrder.methodCalls());
    }

    @Test
    void savedApiDraftRequiresProjectGrantAndPerApiAclThenRunsOnceWithAuditedTrace() throws Exception {
        fixture.prepareAcceptedControllerApi();
        Fixture.ControllerApiDraft draft = fixture.createSavedControllerApiDraft();
        Map<String, Object> input = Map.of("workflowId", draft.workflowId(),
                "expectedRevision", draft.revision(),
                "inputParams", Map.of("orderId", "O-321", "detailLevel", "full"));
        HttpResponse<byte[]> unsigned = fixture.runtimeRequest("POST",
                "/internal/runtime/workflows/studio/read-only-trials",
                fixture.json.writeValueAsBytes(input), Map.of());
        assertEquals(401, unsigned.statusCode());
        assertEquals(0, fixture.controllerOrder.methodCalls());
        HttpResponse<byte[]> anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                        fixture.controlBridge.baseUrl() + "/api/workflows/studio/read-only-trials"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(fixture.json.writeValueAsBytes(input))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(401, anonymous.statusCode());

        HttpResponse<byte[]> permissionDenied = fixture.trialRequest(input);
        assertEquals(403, permissionDenied.statusCode(), fixture.controlBridge.lastResponseSummary());
        fixture.grantTrialPermissions();
        fixture.database.jdbc().update("UPDATE control_platform_user_role SET scope_value = 'blocked' "
                + "WHERE scope_type = 'PROJECT' AND scope_value = 'orders'");
        assertEquals(403, fixture.trialRequest(input).statusCode());
        fixture.database.jdbc().update("UPDATE control_platform_user_role SET scope_value = 'orders' "
                + "WHERE scope_type = 'PROJECT' AND scope_value = 'blocked'");
        HttpResponse<byte[]> aclDenied = fixture.trialRequest(input);
        assertEquals(403, aclDenied.statusCode(), fixture.controlBridge.lastResponseSummary());
        assertEquals("HTTP_API_TRIAL_ACL_DENIED", fixture.responseMap(aclDenied).get("errorCode"));
        assertEquals(0, fixture.controllerOrder.methodCalls());

        fixture.grantApiTrialAcl();
        var staleInput = new LinkedHashMap<>(input); staleInput.put("expectedRevision", "outdated");
        assertEquals(409, fixture.trialRequest(staleInput).statusCode());
        var forgedInput = new LinkedHashMap<>(input); forgedInput.put("identity", Map.of("projectTrusted", true));
        assertEquals(400, fixture.trialRequest(forgedInput).statusCode());
        assertEquals(0, fixture.controllerOrder.methodCalls());
        HttpResponse<byte[]> accepted = fixture.trialRequest(input);
        assertEquals(200, accepted.statusCode(), fixture.controlBridge.lastResponseSummary());
        Map<String, Object> result = fixture.responseMap(accepted);
        assertEquals(Boolean.TRUE, result.get("success"), String.valueOf(result));
        assertEquals("PAID", fixture.map(result.get("apiOutput"), "API result").get("state"));
        assertEquals("PAID", fixture.map(result.get("variables"), "trial variables").get("order_state"));
        assertEquals(1, fixture.controllerOrder.methodCalls());
        assertFalse(String.valueOf(result).contains("synthetic-controller-value"));
        String traceId = Fixture.requiredText(result, "traceId");
        List<Map<String, Object>> runs = fixture.database.jdbc().queryForList(
                "SELECT entry_type, workflow_id, workflow_version_id, snapshot_json "
                        + "FROM runtime_run WHERE trace_id = ?", traceId);
        assertEquals(1, runs.size());
        assertEquals("STUDIO_READ_ONLY_TRIAL", runs.get(0).get("entry_type"));
        assertEquals(draft.workflowId(), runs.get(0).get("workflow_id"));
        assertNull(runs.get(0).get("workflow_version_id"));
        assertTrue(String.valueOf(runs.get(0).get("snapshot_json")).contains(draft.revision()));
        assertTrue(String.valueOf(runs.get(0).get("snapshot_json")).contains("apiQualifiedName"));
        assertEquals(0, fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM runtime_workflow_http_api_pin WHERE workflow_id = ?", Integer.class,
                draft.workflowId()));
        List<Map<String, Object>> spans = fixture.database.jdbc().queryForList(
                "SELECT span_type, node_id, metadata_json FROM runtime_trace_span WHERE trace_id = ?", traceId);
        assertTrue(spans.stream().anyMatch(span -> "api-node".equals(span.get("node_id"))));
        assertTrue(spans.stream().anyMatch(span -> "variable_1790214522008".equals(span.get("node_id"))));
        assertTrue(String.valueOf(spans).contains("STUDIO_READ_ONLY_TRIAL"));
        assertTrue(String.valueOf(spans).contains("graphSha256"));
        assertTrue(String.valueOf(spans).contains("sourceSetRevision"));
        assertFalse(String.valueOf(spans).contains("synthetic-controller-value"));
        fixture.assertPublicRunOps(new Fixture.McpInvocation("", String.valueOf(result.get("runId")), traceId));

        fixture.database.jdbc().update("UPDATE capability_http_api_source_binding "
                + "SET status = 'REMOVED', removed_at = CURRENT_TIMESTAMP WHERE asset_id = 1");
        HttpResponse<byte[]> changed = fixture.trialRequest(input);
        assertEquals(409, changed.statusCode(), fixture.controlBridge.lastResponseSummary());
        assertEquals(1, fixture.controllerOrder.methodCalls());
    }

    @Test
    void signedTrialCannotChangeTargetProjectActorOrBodyAndNonceCannotReplay() throws Exception {
        fixture.prepareAcceptedControllerApi();
        Fixture.ControllerApiDraft draft = fixture.createSavedControllerApiDraft();
        var owner = fixture.httpApiCatalog.detail(1L).summary();
        String graph = fixture.controllerApiBrowserGraph("graphSpecJson");
        String path = "/internal/runtime/workflows/studio/read-only-trials";
        for (int mutation = 0; mutation < 4; mutation++) {
            var command = new com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy.TrialCommand(
                    1, draft.workflowId(), draft.revision(),
                    com.enterprise.ai.common.capability.HttpApiDraftTrialPolicy.graphSha256(graph),
                    mutation == 1 ? 42L : 41L, "orders",
                    mutation == 2 ? "forged-actor" : String.valueOf(fixture.platformUserId),
                    List.of(new com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy.AllowedTarget(
                            "api-node", mutation == 0 ? 2L : 1L, owner.qualifiedName(), "dev",
                            owner.acceptedContractHash(), owner.sourceSetRevision())),
                    Map.of("orderId", "O-321"), System.currentTimeMillis() + 40_000);
            byte[] body = fixture.json.writeValueAsBytes(command);
            Map<String, String> headers = new InternalServiceAuthSigner(INTERNAL_SECRET).sign("POST", path,
                    InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "orders",
                    String.valueOf(fixture.platformUserId), body);
            HttpResponse<byte[]> response = fixture.runtimeRequest("POST", path,
                    mutation == 3 ? "{}".getBytes(StandardCharsets.UTF_8) : body, headers);
            assertEquals(mutation < 2 ? 409 : 401, response.statusCode());
            if (mutation < 3) assertEquals(401, fixture.runtimeRequest("POST", path, body, headers).statusCode(),
                    "a verified signed request must consume its nonce even when the command is rejected");
            assertEquals(0, fixture.controllerOrder.methodCalls());
        }
    }

    @Test
    void savedMethodDraftHasOneSdkDispatchSignedProjectNoBusinessUserAndExactTraceThenAclDenial() throws Exception {
        var surface = fixture.prepareMethodBrowserSurface();
        String revision = Fixture.requiredText(fixture.responseMap(fixture.runtimeRequest("GET", "/api/workflows/" + surface.workflowId() + "/working-copy", null, Map.of())), "revision");
        var input = Map.<String, Object>of("workflowId", surface.workflowId(), "expectedRevision", revision, "inputParams", Map.of("orderNo", "A-1024"));
        assertEquals(403, fixture.trialRequest(input).statusCode());
        fixture.grantTrialPermissions();
        assertEquals(403, fixture.trialRequest(input).statusCode());
        assertEquals(0, fixture.sdkBridge.requestCount()); assertEquals(0, fixture.capabilityHits.get());
        fixture.grantMethodTrialAcl();
        var invalid = new LinkedHashMap<>(input); invalid.put("inputParams", Map.of());
        assertEquals(409, fixture.trialRequest(invalid).statusCode()); assertEquals(0, fixture.sdkBridge.requestCount());
        var result = fixture.trialRequest(input);
        assertEquals(200, result.statusCode(), fixture.controlBridge.lastResponseSummary());
        var body = fixture.responseMap(result);
        assertEquals(Boolean.TRUE, body.get("success"), body.toString());
        assertEquals("N-A-1024", fixture.map(body.get("methodOutput"), "method result").get("data"));
        assertEquals("N-A-1024", fixture.map(body.get("variables"), "trial variables").get("normalized_order_no"));
        fixture.completeMethodBrowserTrial(surface);
        fixture.revokeMethodTrialAcl();
        assertEquals(403, fixture.trialRequest(input).statusCode());
        fixture.assertMethodBrowserTrialDeniedWithoutAnotherDispatch(surface);
    }

    @Test
    void methodEndpointResponseLossKeepsOneActualDispatchAndFailedRunWithoutGraphRetry() throws Exception {
        var surface = fixture.prepareMethodBrowserSurface();
        fixture.grantTrialPermissions(); fixture.grantMethodTrialAcl();
        String revision = Fixture.requiredText(fixture.responseMap(fixture.runtimeRequest("GET", "/api/workflows/" + surface.workflowId() + "/working-copy", null, Map.of())), "revision");
        fixture.sdkBridge.dropNextResponseAfterEndpoint();
        var result = fixture.trialRequest(Map.of("workflowId", surface.workflowId(), "expectedRevision", revision,
                "inputParams", Map.of("orderNo", "A-1024")));
        assertEquals(200, result.statusCode(), fixture.controlBridge.lastResponseSummary());
        var body = fixture.responseMap(result);
        assertEquals(Boolean.FALSE, body.get("success"), body.toString());
        assertNotNull(body.get("traceId")); assertNotNull(body.get("errorCode"));
        assertEquals(1, fixture.sdkBridge.requestCount(), "actual endpoint HTTP request, not a mocked transport call");
        assertEquals(1, fixture.capabilityHits.get(), "SDK business method executed before response loss");
        var runs = fixture.database.jdbc().queryForList("SELECT status, snapshot_json FROM runtime_run WHERE workflow_id = ? AND entry_type = 'STUDIO_READ_ONLY_TRIAL'", surface.workflowId());
        assertEquals(1, runs.size()); assertEquals("FAILED", runs.get(0).get("status"));
        assertFalse(String.valueOf(runs.get(0).get("snapshot_json")).contains("N-A-1024"));
        assertEquals(0, fixture.database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_version WHERE workflow_id = ?", Integer.class, surface.workflowId()));
    }

    @Test
    void signedMethodTrialRejectsWrongTargetProjectActorRevisionGraphAndReplayBeforeSdk() throws Exception {
        fixture.registerAndSync(); var draft = fixture.createSavedMethodTrialDraft();
        var owner = fixture.json.convertValue(fixture.reviewGateway.getBusinessMethodInvocationContext("bmapi2d:normalizeOrderNo", String.valueOf(fixture.platformUserId)).getBody(),
                com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts.InvocationContext.class);
        var target = com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy.target(json, methodTrialGraph());
        String path = "/internal/runtime/workflows/studio/read-only-trials";
        for (int mutation = 0; mutation < 6; mutation++) {
            var allowed = com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy.AllowedTarget.businessMethod(target, owner);
            if (mutation == 0) allowed = new com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy.AllowedTarget("other", 0, owner.qualifiedName(), null, owner.acceptedContractHash(), null, "BUSINESS_METHOD", owner.name(), owner.sourceContractHash(), owner.executionRevision());
            String storedGraph = fixture.database.jdbc().queryForObject("SELECT graph_spec_json FROM runtime_workflow WHERE id = ?", String.class, draft.workflowId());
            var command = new com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy.TrialCommand(1, draft.workflowId(), mutation == 3 ? "old" : draft.revision(),
                    mutation == 4 ? "f".repeat(64) : com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy.graphSha256(storedGraph),
                    mutation == 1 ? 999L : fixture.projectId(), PROJECT_CODE, mutation == 2 ? "forged" : String.valueOf(fixture.platformUserId),
                    List.of(allowed), Map.of("orderNo", "A-1024"), System.currentTimeMillis() + 40_000);
            byte[] body = fixture.json.writeValueAsBytes(command);
            var headers = new InternalServiceAuthSigner(INTERNAL_SECRET).sign("POST", path, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                    PROJECT_CODE, String.valueOf(fixture.platformUserId), body);
            assertEquals(401, fixture.runtimeRequest("POST", path, body, Map.of()).statusCode());
            var response = fixture.runtimeRequest("POST", path, mutation == 5 ? "{}".getBytes(StandardCharsets.UTF_8) : body, headers);
            assertEquals(mutation == 2 || mutation == 5 ? 401 : 409, response.statusCode(), fixture.runtimeBridge.lastResponseSummary());
            if (mutation != 5) assertEquals(401, fixture.runtimeRequest("POST", path, body, headers).statusCode());
            assertEquals(0, fixture.sdkBridge.requestCount()); assertEquals(0, fixture.capabilityHits.get());
        }
    }

    @Test
    void methodSourceAcceptanceAndCredentialChangesRejectAtControlWithZeroSdkCalls() throws Exception {
        fixture.registerAndSync(); fixture.grantTrialPermissions(); fixture.grantMethodTrialAcl();
        var draft = fixture.createSavedMethodTrialDraft();
        var input = Map.<String, Object>of("workflowId", draft.workflowId(), "expectedRevision", draft.revision(), "inputParams", Map.of("orderNo", "A-1024"));
        String hash = fixture.database.jdbc().queryForObject("SELECT accepted_contract_hash FROM capability_source_state WHERE qualified_name = 'bmapi2d:normalizeOrderNo'", String.class);
        for (String column : List.of("source_contract_hash", "accepted_contract_hash")) {
            fixture.database.jdbc().update("UPDATE capability_source_state SET " + column + " = ? WHERE qualified_name = 'bmapi2d:normalizeOrderNo'", "f".repeat(64));
            assertEquals(409, fixture.trialRequest(input).statusCode());
            fixture.database.jdbc().update("UPDATE capability_source_state SET " + column + " = ? WHERE qualified_name = 'bmapi2d:normalizeOrderNo'", hash);
        }
        fixture.database.jdbc().update("UPDATE capability_business_method_asset SET enabled = 0 WHERE qualified_name = 'bmapi2d:normalizeOrderNo'");
        assertEquals(409, fixture.trialRequest(input).statusCode());
        fixture.database.jdbc().update("UPDATE capability_business_method_asset SET enabled = 1 WHERE qualified_name = 'bmapi2d:normalizeOrderNo'");
        fixture.database.jdbc().update("UPDATE capability_registry_project_credential SET status = 'INACTIVE' WHERE project_code = ?", PROJECT_CODE);
        assertEquals(409, fixture.trialRequest(input).statusCode());
        assertEquals(0, fixture.sdkBridge.requestCount()); assertEquals(0, fixture.capabilityHits.get());
    }

    @Test
    void signedMethodTrialRejectsChangedExecutionRevisionBeforeSdk() throws Exception {
        fixture.registerAndSync(); fixture.grantTrialPermissions(); fixture.grantMethodTrialAcl();
        var draft = fixture.createSavedMethodTrialDraft();
        var oldOwner = fixture.json.convertValue(fixture.reviewGateway.getBusinessMethodInvocationContext(
                "bmapi2d:normalizeOrderNo", String.valueOf(fixture.platformUserId)).getBody(),
                com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts.InvocationContext.class);
        var target = com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy.target(json, methodTrialGraph());
        String storedGraph = fixture.database.jdbc().queryForObject(
                "SELECT graph_spec_json FROM runtime_workflow WHERE id=?", String.class, draft.workflowId());
        var allowed = com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy.AllowedTarget.businessMethod(target, oldOwner);
        Long credentialId = fixture.database.jdbc().queryForObject(
                "SELECT id FROM capability_registry_project_credential WHERE project_code=?", Long.class, PROJECT_CODE);
        fixture.registrySecurity.updateAdministrativePolicy(credentialId, List.of("https://trial.orders.test"), List.of(), 600, "ACTIVE");
        String path = "/internal/runtime/workflows/studio/read-only-trials";
        var command = new com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy.TrialCommand(1,
                draft.workflowId(), draft.revision(), com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy.graphSha256(storedGraph),
                fixture.projectId(), PROJECT_CODE, String.valueOf(fixture.platformUserId), List.of(allowed),
                Map.of("orderNo", "A-1024"), System.currentTimeMillis() + 40_000);
        byte[] body = fixture.json.writeValueAsBytes(command);
        var headers = new InternalServiceAuthSigner(INTERNAL_SECRET).sign("POST", path,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, PROJECT_CODE, String.valueOf(fixture.platformUserId), body);
        var denied = fixture.runtimeRequest("POST", path, body, headers);
        assertEquals(409, denied.statusCode());
        assertEquals("BUSINESS_METHOD_TRIAL_OWNER_CHANGED", fixture.responseMap(denied).get("errorCode"));
        assertEquals(0, fixture.sdkBridge.requestCount()); assertEquals(0, fixture.capabilityHits.get());
        assertEquals(0, fixture.database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM runtime_run WHERE workflow_id=?", Integer.class, draft.workflowId()));

        var freshOwner = fixture.json.convertValue(fixture.reviewGateway.getBusinessMethodInvocationContext(
                "bmapi2d:normalizeOrderNo", String.valueOf(fixture.platformUserId)).getBody(),
                com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts.InvocationContext.class);
        assertEquals(oldOwner.currentContractHash(), freshOwner.currentContractHash());
        assertNotEquals(oldOwner.executionRevision(), freshOwner.executionRevision());
        var refreshed = new com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy.TrialCommand(1,
                draft.workflowId(), draft.revision(), command.graphSha256(), fixture.projectId(), PROJECT_CODE,
                String.valueOf(fixture.platformUserId), List.of(
                        com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy.AllowedTarget.businessMethod(target, freshOwner)),
                command.inputParams(), System.currentTimeMillis() + 40_000);
        byte[] freshBody = fixture.json.writeValueAsBytes(refreshed);
        var freshHeaders = new InternalServiceAuthSigner(INTERNAL_SECRET).sign("POST", path,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, PROJECT_CODE, String.valueOf(fixture.platformUserId), freshBody);
        assertEquals(200, fixture.runtimeRequest("POST", path, freshBody, freshHeaders).statusCode());
        assertEquals(1, fixture.sdkBridge.requestCount()); assertEquals(1, fixture.capabilityHits.get());
    }

    private static String methodTrialGraph() {
        return """
                {"schemaVersion":2,"nodes":[
                 {"id":"method","name":"Normalize order number","type":"TOOL","ref":{"kind":"TOOL","name":"bmapi2d_normalizeOrderNo","qualifiedName":"bmapi2d:normalizeOrderNo","projectCode":"bmapi2d"},
                  "config":{"inputMapping":{"orderNo":"params.orderNo"},"outputAlias":"method_result"}},
                 {"id":"variable","name":"Normalized order number","type":"VARIABLE_ASSIGN","config":{"assignments":{"normalized_order_no":"nodeOutput.method.data"}}}],
                 "edges":[{"id":"method-variable","from":"method","to":"variable","condition":"always"}],
                 "entryNodeId":"method","exitNodeIds":["variable"]}
                """;
    }

    private static String workflowGraph() {
        return """
                {"schemaVersion":2,
                 "inputSchema":{"type":"object","properties":{"orderNo":{"type":"string"},"request":{"type":"object"}}},
                 "nodes":[
                   {"id":"health","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"bmapi2d:health"},"config":{"args":{}}},
                   {"id":"normalize","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"bmapi2d:normalizeOrderNo"},"config":{"inputMapping":{"orderNo":"params.orderNo"}}},
                   {"id":"query","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"bmapi2d:queryOrder"},"config":{"inputMapping":{"request":"params.request"},"outputAlias":"order_result"}}
                 ],
                 "edges":[{"id":"health-normalize","from":"health","to":"normalize"},{"id":"normalize-query","from":"normalize","to":"query"}],
                 "entryNodeId":"health","exitNodeIds":["query"]}
                """;
    }

    private static String publishedWorkflowGraph() {
        return """
                {"schemaVersion":2,
                 "inputSchema":{"type":"object","properties":{"orderNo":{"type":"string"},"request":{"type":"object"}},"required":["orderNo","request"]},
                 "nodes":[
                   {"id":"health","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"bmapi2d:health"},"config":{"args":{}}},
                   {"id":"normalize","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"bmapi2d:normalizeOrderNo"},"config":{"inputMapping":{"orderNo":"orderNo"}}},
                   {"id":"query","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"bmapi2d:queryOrder"},"config":{"inputMapping":{"request":"request"},"outputAlias":"order_result"}}
                 ],
                 "edges":[{"id":"health-normalize","from":"health","to":"normalize"},{"id":"normalize-query","from":"normalize","to":"query"}],
                 "entryNodeId":"health","exitNodeIds":["query"]}
                """;
    }

    private static String workflowCanvas() {
        return """
                {"schemaVersion":1,"layoutVersion":1,
                 "nodes":[{"id":"health","position":{"x":0,"y":0}},{"id":"normalize","position":{"x":240,"y":0}},{"id":"query","position":{"x":480,"y":0}}],
                 "edges":[{"id":"health-normalize"},{"id":"normalize-query"}]}
                """;
    }

    private static void assertMethod(List<Map<String, Object>> methods,
                                     String name,
                                     String qualifiedName,
                                     int inputCount) {
        Map<String, Object> found = methods.stream()
                .filter(method -> name.equals(method.get("name")))
                .findFirst().orElseThrow(() -> new AssertionError("missing method " + name + "; actual=" + methods));
        assertEquals(qualifiedName, found.get("qualifiedName"));
        assertEquals(qualifiedName, found.get("sourceQualifiedName"));
        assertEquals(inputCount, ((List<?>) found.get("parameters")).size());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(Map<String, Object> page) {
        Object raw = page.get("records");
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, item) -> copy.put(String.valueOf(key), item));
                result.add(copy);
            }
        }
        return result;
    }

    @Configuration
    @EnableTransactionManagement
    static class Transactions {
    }

    static final class Fixture implements AutoCloseable {
        private final ObjectMapper json;
        private final AtomicInteger capabilityHits = new AtomicInteger();
        private final InvocationEvidence invocationEvidence = new InvocationEvidence();
        private final StatefulWriteBusinessFixture writeBusiness = new StatefulWriteBusinessFixture();
        private final List<AutoCloseable> closers = new ArrayList<>();
        private RuntimeQueryTestDatabase database;
        private AnnotationConfigApplicationContext transactions;
        private TransactionTemplate tx;
        private CapabilityRegistryService registry;
        private RegistrySecurityService registrySecurity;
        private CapabilityToolCatalogService catalog;
        private CapabilityReviewGateway reviewGateway;
        private CapabilityToolExecutionService capabilityExecution;
        private LoopbackBridge capabilityBridge;
        private LoopbackBridge sdkBridge;
        private LoopbackBridge controlBridge;
        private LoopbackBridge runtimeBridge;
        private Path credentialFile;
        private String platformBearer;
        private Long platformUserId;
        private String browserWorkflowId;
        private ReachAiRegistryProperties sdkProperties;
        private ReachAiRegistryClient starter;
        private RuntimeGraphSpecExecutor runtimeGraphExecutor;
        private RuntimeWorkflowReferenceIndex workflowReferences;
        private RuntimeRunOpsQueryService runOpsQuery;
        private RuntimeWorkflowVersionMapper workflowVersions;
        private HttpApiCatalogService httpApiCatalog;
        private final ControllerOrderFixtureController controllerOrder = new ControllerOrderFixtureController();
        private LoopbackBridge controllerOrderBridge;
        private RuntimeHttpApiConnectionService httpApiConnections;
        private Path multiSourceRoot;
        private String multiSourceWorkflowId;
        private volatile boolean changeReferenceUnavailable;
        private Long changePublicationId;
        private Long changeClientId;
        private McpSurface changeMcp;
        private Long changeOriginalVersionId;
        private String changeOriginalSnapshot;
        private String changeOriginalPins;
        private String changeSourceGuardDraftId;
        private Long changeOriginalMcpRevisionId;
        private int changeAclCount;
        private Path writeApiRoot;
        final StatefulWriteApiFixtureController writeApi = new StatefulWriteApiFixtureController();
        private LoopbackBridge writeApiBridge;
        private String writeApiCredentialRef;
        private Long writeApiCredentialId;
        private WriteWorkflowAgentFixture writeWorkflowAgent;

        Fixture(ObjectMapper json) {
            this.json = json;
        }

        void start() throws Exception {
            start(false);
        }

        void startWriteFixture() throws Exception {
            start(true);
        }

        private void start(boolean writeFixture) throws Exception {
            database = new RuntimeQueryTestDatabase(tables(), mapperTypes());
            if (writeFixture) database.addInterceptor(new com.enterprise.ai.capability.config.CapabilityMybatisPlusConfiguration()
                    .capabilityMybatisPlusInterceptor());
            normalizeH2JsonTextColumns();
            for (String table : List.of("runtime_workflow_capability_reference", "runtime_agent",
                    "runtime_agent_config_version", "runtime_agent_workflow_tool", "control_mcp_publication",
                    "control_mcp_publication_item", "control_mcp_publication_revision", "control_mcp_client",
                    "control_mcp_call_log", "control_a2a_publication", "control_a2a_publication_revision",
                    "runtime_agent_skill_binding", "runtime_agent_remote_agent_binding",
                    "runtime_interaction_session", "runtime_interaction_event")) {
                createH2Table(table);
            }
            for (String table : List.of("control_platform_auth_provider", "control_platform_auth_audit_event",
                    "runtime_agent_workflow_credential", "capability_http_api_asset",
                    "capability_http_api_source_binding", "capability_http_api_inventory_state",
                    "capability_http_api_inventory_member", "capability_http_api_acceptance",
                    "runtime_http_api_connection", "runtime_workflow_http_api_pin", "runtime_console_capability_invocation",
                    "capability_scan_module", "capability_semantic_doc",
                    "capability_external_api_source", "capability_external_api_provider", "capability_external_api_entry",
                    "capability_external_api_version", "capability_external_api_operation", "capability_external_api_verification",
                    "capability_project_external_api", "capability_project_external_api_operation")) {
                createH2Table(table);
            }
            closers.add(database);
            DataSourceTransactionManager manager = new DataSourceTransactionManager(database.jdbc().getDataSource());
            tx = new TransactionTemplate(manager);
            transactions = new AnnotationConfigApplicationContext();
            transactions.register(Transactions.class);
            transactions.registerBean("transactionManager", DataSourceTransactionManager.class, () -> manager);

            SqlSessionTemplate session = session(database);
            ScanProjectMapper projects = session.getMapper(ScanProjectMapper.class);
            CapabilitySnapshotMapper snapshots = session.getMapper(CapabilitySnapshotMapper.class);
            CapabilityDiffItemMapper differences = session.getMapper(CapabilityDiffItemMapper.class);
            CapabilitySourceStateMapper sourceStates = session.getMapper(CapabilitySourceStateMapper.class);
            CapabilityChangePolicy policy = new CapabilityChangePolicy(json);
            CapabilityChangeLifecycle lifecycle = new CapabilityChangeLifecycle(
                    snapshots, differences, sourceStates, policy, session.getMapper(CapabilitySyncReceiptMapper.class));
            registrySecurity = new RegistrySecurityService(
                    session.getMapper(RegistryCredentialMapper.class), json);
            RegistryEnrollmentService enrollment = new RegistryEnrollmentService(
                    session.getMapper(RegistryEnrollmentTokenMapper.class), projects);
            RegistryProjectRegistrationService registration = new RegistryProjectRegistrationService(
                    projects, registrySecurity, enrollment);
            CapabilitySourceContractGuard guard = new CapabilitySourceContractGuard(lifecycle, policy);

            transactions.registerBean(BusinessMethodAssetStore.class, () -> new BusinessMethodAssetStore(
                    session.getMapper(BusinessMethodAssetMapper.class), session.getMapper(BusinessMethodRevisionMapper.class),
                    snapshots, differences, policy, json));
            transactions.registerBean(CapabilityCatalogProjectionStore.class, () -> new CapabilityCatalogProjectionStore(
                    session.getMapper(ScanProjectToolMapper.class), session.getMapper(ToolDefinitionMapper.class), json, policy,
                    transactions.getBean(BusinessMethodAssetStore.class)));
            transactions.registerBean(RegistryInstanceLifecycleService.class, () -> new RegistryInstanceLifecycleService(
                    session.getMapper(ProjectInstanceMapper.class), json));
            transactions.registerBean(CapabilityReviewEvidenceStore.class, () -> new CapabilityReviewEvidenceStore(
                    snapshots, differences, session.getMapper(CapabilityApplyRecordMapper.class)));
            transactions.registerBean(StarterMvcHttpApiIntakeService.class, () -> new StarterMvcHttpApiIntakeService(
                    org.mockito.Mockito.mock(com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetService.class),
                    org.mockito.Mockito.mock(com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingMapper.class), json));
            transactions.registerBean(CapabilitySourceIntakeService.class, () -> new CapabilitySourceIntakeService(
                    projects, session.getMapper(ScanProjectToolMapper.class), session.getMapper(ToolDefinitionMapper.class),
                    session.getMapper(CapabilitySyncLogMapper.class), snapshots, differences, json, policy, lifecycle,
                    transactions.getBean(CapabilityCatalogProjectionStore.class),
                    transactions.getBean(CapabilityReviewEvidenceStore.class),
                    transactions.getBean(StarterMvcHttpApiIntakeService.class),
                    transactions.getBean(BusinessMethodAssetStore.class)));
            transactions.registerBean(CapabilityRegistryService.class, () -> new CapabilityRegistryService(
                    projects, snapshots, differences, registrySecurity, registration, json, policy, lifecycle,
                    transactions.getBean(CapabilityCatalogProjectionStore.class),
                    transactions.getBean(RegistryInstanceLifecycleService.class),
                    transactions.getBean(CapabilitySourceIntakeService.class),
                    transactions.getBean(CapabilityReviewEvidenceStore.class)));
            transactions.refresh();
            registry = transactions.getBean(CapabilityRegistryService.class);
            catalog = new CapabilityToolCatalogService(session.getMapper(ToolDefinitionMapper.class), projects,
                    session.getMapper(ScanProjectToolMapper.class), json);
            HttpApiSourceBindingMapper apiBindings = session.getMapper(HttpApiSourceBindingMapper.class);
            httpApiCatalog = transactional(new HttpApiCatalogService(session.getMapper(HttpApiAssetMapper.class),
                    apiBindings, session.getMapper(HttpApiInventoryStateMapper.class),
                    session.getMapper(HttpApiInventoryMemberMapper.class),
                    session.getMapper(HttpApiAcceptanceMapper.class), projects, json));
            HttpApiAssetService apiAssets = transactional(new HttpApiAssetService(session.getMapper(HttpApiAssetMapper.class),
                    apiBindings, new HttpApiContractCanonicalizer(json)));
            var apiInventory = new HttpApiInventoryStateService(session.getMapper(HttpApiInventoryStateMapper.class),
                    session.getMapper(HttpApiInventoryMemberMapper.class));
            CapabilityScannerClient scanner = org.mockito.Mockito.mock(CapabilityScannerClient.class);
            org.mockito.Mockito.when(scanner.scanController(org.mockito.ArgumentMatchers.any())).thenAnswer(call ->
                    actualScannerManifest(call.getArgument(0), true));
            org.mockito.Mockito.when(scanner.scanOpenApi(org.mockito.ArgumentMatchers.any())).thenAnswer(call ->
                    actualScannerManifest(call.getArgument(0), false));
            CapabilityScanProjectCatalogService scanProjects = transactional(new CapabilityScanProjectCatalogService(
                    projects,
                    session.getMapper(ScanProjectToolMapper.class),
                    session.getMapper(ScanModuleMapper.class),
                    session.getMapper(SemanticDocMapper.class),
                    session.getMapper(RegistryCredentialMapper.class),
                    registrySecurity,
                    org.mockito.Mockito.mock(CapabilityScanProjectBlockerService.class),
                    session.getMapper(ToolDefinitionMapper.class),
                    scanner,
                    session.getMapper(ProjectInstanceMapper.class),
                    snapshots,
                    json,
                    transactional(new ControllerScanHttpApiIntakeService(apiAssets, apiBindings, apiInventory)),
                    transactional(new OpenApiScanHttpApiIntakeService(apiAssets, apiBindings, apiInventory))));
            var unusedModel = org.mockito.Mockito.mock(com.enterprise.ai.capability.catalog.scan.CapabilityModelClient.class);
            var semanticCatalog = new com.enterprise.ai.capability.catalog.semantic.CapabilitySemanticCatalogService(
                    session.getMapper(SemanticDocMapper.class), projects, session.getMapper(ScanModuleMapper.class),
                    session.getMapper(ScanProjectToolMapper.class), session.getMapper(ToolDefinitionMapper.class), unusedModel, json);
            var sensitiveTasks = new com.enterprise.ai.capability.catalog.scan.CapabilitySensitiveDataScanOrchestrator(
                    scanProjects, new com.enterprise.ai.capability.catalog.scan.CapabilitySensitiveDataScanService(
                    json, unusedModel, session.getMapper(ScanProjectToolMapper.class)));
            var marketBindings = new com.enterprise.ai.capability.externalapi.ApiMarketHttpApiBindingService(apiAssets,
                    session.getMapper(HttpApiAssetMapper.class), apiBindings,
                    session.getMapper(com.enterprise.ai.capability.externalapi.ProjectExternalApiMapper.class),
                    session.getMapper(com.enterprise.ai.capability.externalapi.ProjectExternalApiOperationMapper.class),
                    session.getMapper(com.enterprise.ai.capability.externalapi.ExternalApiEntryMapper.class),
                    session.getMapper(com.enterprise.ai.capability.externalapi.ExternalApiVersionMapper.class),
                    session.getMapper(com.enterprise.ai.capability.externalapi.ExternalApiOperationMapper.class),
                    new HttpApiContractCanonicalizer(json), json);
            httpApiCatalog.setMarketBindings(marketBindings);
            var market = transactional(new com.enterprise.ai.capability.externalapi.ExternalApiCatalogService(
                    session.getMapper(com.enterprise.ai.capability.externalapi.ExternalApiSourceMapper.class),
                    session.getMapper(com.enterprise.ai.capability.externalapi.ExternalApiProviderMapper.class),
                    session.getMapper(com.enterprise.ai.capability.externalapi.ExternalApiEntryMapper.class),
                    session.getMapper(com.enterprise.ai.capability.externalapi.ExternalApiVersionMapper.class),
                    session.getMapper(com.enterprise.ai.capability.externalapi.ExternalApiOperationMapper.class),
                    session.getMapper(com.enterprise.ai.capability.externalapi.ExternalApiVerificationMapper.class),
                    session.getMapper(com.enterprise.ai.capability.externalapi.ProjectExternalApiMapper.class),
                    session.getMapper(com.enterprise.ai.capability.externalapi.ProjectExternalApiOperationMapper.class),
                    projects, json, marketBindings));

            CapabilityInternalAuthProperties capabilityAuth = new CapabilityInternalAuthProperties(
                    INTERNAL_SECRET, 300, 600, 10_000, 1_048_576);
            CapabilityInternalAuthVerifier capabilityVerifier = new CapabilityInternalAuthVerifier(
                    capabilityAuth, new JdbcCapabilityInternalAuthNonceStore(database.jdbc()), json);
            CapabilityInternalAuthFilter capabilityFilter = new CapabilityInternalAuthFilter(
                    capabilityVerifier, capabilityAuth);

            sdkProperties = new ReachAiRegistryProperties();
            sdkProperties.getProject().setCode(PROJECT_CODE);
            sdkProperties.getProject().setName("BMAPI 2D isolated fixture");
            sdkProperties.getProject().setEnvironment("test");
            sdkProperties.getProject().setInstanceId("bmapi-2d-fixture");
            sdkProperties.getCapability().setScanMode(ReachAiRegistryProperties.ScanMode.ANNOTATED_ONLY);
            sdkProperties.getCapability().setRequireInvocationToken(true);
            AnnotationConfigApplicationContext sdkContext = new AnnotationConfigApplicationContext();
            if (writeFixture) {
                sdkProperties.getProject().setName("BMAPI 2E isolated order notes");
                sdkContext.registerBean(StatefulWriteBusinessFixture.class, () -> writeBusiness);
            } else {
                sdkContext.registerBean(DeterministicBusinessFixture.class,
                        () -> new DeterministicBusinessFixture(capabilityHits, invocationEvidence));
            }
            sdkContext.refresh();
            closers.add(sdkContext);
            ReachCapabilityEndpoint sdkEndpoint = new ReachCapabilityEndpoint(
                    new ReachCapabilityInvoker(sdkContext), new ReachCapabilityInvocationVerifier(sdkProperties),
                    List.of((context, invocation) -> {
                        invocationEvidence.signedProject.set(context.getProjectCode());
                        invocationEvidence.signedTenant.set(context.getTenantId());
                        invocationEvidence.businessUser.set(context.getExternalUserId());
                        invocationEvidence.businessRoles.set(context.getRoles());
                        return invocation.proceed();
                    }));
            sdkBridge = LoopbackBridge.start(MockMvcBuilders.standaloneSetup(sdkEndpoint).build());
            closers.add(sdkBridge);

            CapabilityHttpToolInvoker httpInvoker = new DefaultCapabilityHttpToolInvoker(
                    new RestTemplateBuilder().messageConverters(
                            new ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter(json)),
                    new CapabilityOutboundTransportPolicy(
                    "DEVELOPMENT_PLAINTEXT", sdkBridge.baseUrl(), sdkBridge.baseUrl(), sdkBridge.baseUrl(),
                    new MockEnvironment()),
                    json);
            var businessMethods = new BusinessMethodCatalogService(session.getMapper(BusinessMethodAssetMapper.class),
                    transactions.getBean(BusinessMethodAssetStore.class), projects, lifecycle);
            capabilityExecution = new CapabilityToolExecutionService(businessMethods, httpInvoker, registrySecurity, guard);
            CapabilityInvocationApplicationService invocationApplication = new CapabilityInvocationApplicationService(
                    new CapabilityInvocationAssetResolver(businessMethods),
                    new CapabilityInvocationPolicyChain(),
                    new CapabilityInvokerRegistry(List.of(new CatalogHttpCapabilityInvoker(capabilityExecution))));
            MockMvc capabilityMvc = MockMvcBuilders.standaloneSetup(
                    new CapabilityRegistryCompatibilityController(registry),
                    new CapabilityRegistryOperationsCompatibilityController(registry),
                    new BusinessMethodCatalogInternalController(businessMethods),
                    new com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodInvocationContextInternalController(
                            new com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodInvocationContextService(
                                    businessMethods, lifecycle, registrySecurity, json)),
                    new HttpApiCatalogInternalController(httpApiCatalog),
                    new com.enterprise.ai.capability.externalapi.ExternalApiCatalogController(market),
                    new CapabilityToolCatalogController(catalog, guard),
                    new CapabilityScanProjectCatalogController(scanProjects, sensitiveTasks),
                    new com.enterprise.ai.capability.catalog.semantic.CapabilitySemanticCatalogController(semanticCatalog),
                    new CapabilityProjectInternalController(new CapabilityProjectLookupService(projects)),
                    new CapabilityToolInternalController(
                            new CapabilityToolLookupService(businessMethods, registrySecurity),
                            capabilityExecution, invocationApplication))
                    .addFilters(capabilityFilter)
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                    .setControllerAdvice(new com.enterprise.ai.capability.externalapi.ExternalApiCatalogExceptionHandler()).build();
            capabilityBridge = LoopbackBridge.start(capabilityMvc);
            closers.add(capabilityBridge);
            sdkProperties.getProject().setBaseUrl(sdkBridge.baseUrl());
            sdkProperties.getRegistry().setUrl(capabilityBridge.baseUrl());
            credentialFile = Files.createTempFile("bmapi-2d-registry-", ".json");
            Files.deleteIfExists(credentialFile);
            sdkProperties.getRegistry().setCredentialStorePath(credentialFile.toString());
            closers.add(() -> Files.deleteIfExists(credentialFile));
            RegistryEnrollmentService.IssuedEnrollment issued = tx.execute(status -> enrollment.create(PROJECT_CODE, 1L));
            assertNotNull(issued);
            sdkProperties.getRegistry().setEnrollmentToken(issued.enrollmentToken());
            starter = new ReachAiRegistryClient(sdkProperties,
                    new ReachCapabilityBeanScanner(sdkContext, sdkProperties));

            reviewGateway = new CapabilityReviewGateway(new InternalServiceAuthSigner(INTERNAL_SECRET), json,
                    capabilityBridge.baseUrl());
            configureRealPlatformSession(session);
            startRuntime(session);
            controlBridge = LoopbackBridge.start(controlMvc(session));
            closers.add(controlBridge);
        }

        void registerWriteMethods(boolean accept) {
            registerAndSync();
            if (accept) acceptLatestPendingSourceChanges();
        }

        void grantWriteAcl() {
            for (String name : List.of(StatefulWriteBusinessFixture.QUALIFIED_NAME, "bmapi2d:identityOnly")) {
                var allow = new ControlToolAclEntity();
                allow.setRoleCode("bmapi-fixture-role"); allow.setProjectId(projectId()); allow.setProjectCode(PROJECT_CODE);
                allow.setTargetKind("TOOL"); allow.setTargetName(name); allow.setPermission("ALLOW"); allow.setEnabled(true);
                allow.setNote("isolated explicit Console write fixture ACL");
                allow.setCreatedAt(LocalDateTime.now()); allow.setUpdatedAt(LocalDateTime.now());
                sessionUnchecked().getMapper(ControlToolAclMapper.class).insert(allow);
            }
        }

        void setWriteAcl(boolean enabled) {
            assertEquals(1, database.jdbc().update("UPDATE control_tool_acl SET enabled=? WHERE target_name=?",
                    enabled, StatefulWriteBusinessFixture.QUALIFIED_NAME));
        }

        void syncWriteDrift() { syncSource(StatefulWriteBusinessFixture.Drift.class, StatefulWriteBusinessFixture.Drift::new); }

        void assertWriteExcludedFromReadOnlyAndDebug() throws Exception {
            var draft = createSavedMethodTrialDraft();
            String graph = methodTrialGraph().replace("bmapi2d_normalizeOrderNo", StatefulWriteBusinessFixture.STORAGE_NAME)
                    .replace("bmapi2d:normalizeOrderNo", StatefulWriteBusinessFixture.QUALIFIED_NAME);
            database.jdbc().update("UPDATE runtime_workflow SET graph_spec_json=? WHERE id=?", graph, draft.workflowId());
            var loaded = runtimeRequest("GET", "/api/workflows/" + draft.workflowId() + "/working-copy", null, Map.of());
            String revision = requiredText(responseMap(loaded), "revision");
            var readOnly = trialRequest(Map.of("workflowId", draft.workflowId(), "expectedRevision", revision,
                    "inputParams", Map.of("orderNo", "ORD-2E")));
            assertEquals(409, readOnly.statusCode());
            assertEquals("BUSINESS_METHOD_TRIAL_READ_ONLY_REQUIRED", responseMap(readOnly).get("errorCode"));
            var debug = runtimeRequest("POST", "/api/workflows/studio/debug-run", json.writeValueAsBytes(Map.of(
                    "workflowId", draft.workflowId(), "inputParams", Map.of("orderNo", "ORD-2E"), "debugOptions", Map.of())), Map.of());
            assertEquals(200, debug.statusCode());
            assertFalse(Boolean.TRUE.equals(responseMap(debug).get("success")));
            var untrusted = runtimeGraphExecutor.execute(graph, Map.of("params", Map.of("orderNo", "ORD-2E")));
            assertFalse(untrusted.success());
            assertTrue(untrusted.answer().contains("BUSINESS_METHOD_DEBUG_IDENTITY_DENIED"));
            assertWriteCounts(0, 0);
        }

        void setWriteSourceAvailability(String availability) {
            assertEquals(1, database.jdbc().update("UPDATE capability_source_state SET availability=? WHERE qualified_name=?",
                    availability, StatefulWriteBusinessFixture.QUALIFIED_NAME));
        }

        Map<String, Object> writeContext(String name) throws Exception {
            var response = writeRequest("GET", "/api/business-methods/" + name + "/invocation-context", null);
            assertEquals(200, response.statusCode());
            return responseMap(response);
        }

        HttpResponse<byte[]> writeRequest(String method, String path, Object body) throws Exception {
            return writeRequestWithToken(method, path, body, platformBearer);
        }

        private HttpResponse<byte[]> writeRequestWithToken(String method, String path, Object body, String token) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create(controlBridge.baseUrl() + path)).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token).header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        HttpResponse<byte[]> writeRequestAsOtherActor(Object body) throws Exception {
            return requestAsOtherActor(PROJECT_CODE,
                    "/api/business-methods/" + StatefulWriteBusinessFixture.STORAGE_NAME + "/invocations", body);
        }

        HttpResponse<byte[]> requestAsOtherActor(String projectCode, String path, Object body) throws Exception {
            var session = sessionUnchecked();
            var user = new PlatformUserEntity();
            user.setUsername("bmapi-2e-other"); user.setDisplayName("Other isolated actor");
            user.setStatus("ACTIVE"); user.setSourceProvider("LOCAL"); user.setExternalSubject("local:other");
            user.setCreatedAt(LocalDateTime.now()); user.setUpdatedAt(LocalDateTime.now());
            session.getMapper(PlatformUserMapper.class).insert(user);
            var role = new PlatformUserRoleEntity(); role.setUserId(user.getId());
            role.setRoleId(database.jdbc().queryForObject("SELECT id FROM control_platform_role WHERE role_code='bmapi-fixture-role'", Long.class));
            role.setScopeType("PROJECT"); role.setScopeValue(projectCode); role.setCreatedAt(LocalDateTime.now());
            session.getMapper(PlatformUserRoleMapper.class).insert(role);
            var codec = new PlatformSessionTokenCodec(); String token = codec.issueRawToken();
            var login = new PlatformLoginSessionEntity(); login.setSessionId("bmapi-2e-other"); login.setUserId(user.getId());
            login.setProvider("LOCAL"); login.setAccessTokenId(codec.digest(token)); login.setExpiresAt(LocalDateTime.now().plusMinutes(30));
            login.setCreatedAt(LocalDateTime.now()); session.getMapper(PlatformLoginSessionMapper.class).insert(login);
            return writeRequestWithToken("POST", path, body, token);
        }

        Map<String, Object> writeResponse(HttpResponse<byte[]> response) throws Exception { return responseMap(response); }

        String writeWorkflowTransportDiagnostic() {
            return "control=" + controlBridge.lastResponseSummary() + ";runtime=" + runtimeBridge.lastResponseSummary();
        }

        void dropNextWriteSdkResponse() { sdkBridge.dropNextResponseAfterEndpoint(); }
        void dropNextWriteControlResponse() { controlBridge.dropNextResponseAfterEndpoint(); }

        void assertWriteCounts(int writes, int ledgerRows) {
            assertEquals(writes, writeBusiness.noteCount(), "actual isolated state");
            assertEquals(writes, writeBusiness.methodHits.get(), "actual Java WRITE method hits");
            assertEquals(writes, sdkBridge.requestCount(), "actual SDK endpoint HTTP requests");
            assertEquals(0, writeBusiness.identityMethodHits.get(), "Console must not impersonate a business user");
            assertEquals(ledgerRows, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_console_capability_invocation", Integer.class));
        }

        void assertWriteAuditSafe(String sensitiveValue) throws Exception {
            for (String table : List.of("runtime_console_capability_invocation", "runtime_run", "runtime_trace_span", "runtime_tool_call_log")) {
                assertFalse(json.writeValueAsString(database.jdbc().queryForList("SELECT * FROM " + table)).contains(sensitiveValue),
                        "raw sensitive input must not be persisted in " + table);
            }
        }

        Map<String, Object> writeEvidence() {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("noteCount", writeBusiness.noteCount()); evidence.put("methodHits", writeBusiness.methodHits.get());
            evidence.put("sdkEndpointRequests", sdkBridge.requestCount()); evidence.put("identityMethodHits", writeBusiness.identityMethodHits.get());
            evidence.put("publicPostRequests", controlBridge.requestsFor("POST", "/api/business-methods/" + StatefulWriteBusinessFixture.STORAGE_NAME + "/invocations"));
            evidence.put("publicQueryRequests", controlBridge.requestsForPrefix("GET", "/api/business-method-invocations/"));
            evidence.put("signedProject", invocationEvidence.signedProject.get());
            evidence.put("signedTenant", invocationEvidence.signedTenant.get());
            evidence.put("businessUserAbsent", invocationEvidence.businessUser.get() == null);
            evidence.put("businessRolesAbsent", invocationEvidence.businessRoles.get() == null || invocationEvidence.businessRoles.get().isEmpty());
            evidence.put("ledger", database.jdbc().queryForList("SELECT invocation_id, run_id, trace_id, status, dispatch_stage, error_code FROM runtime_console_capability_invocation ORDER BY id"));
            evidence.put("runs", database.jdbc().queryForList("SELECT id, trace_id, run_type, entry_type, status FROM runtime_run ORDER BY id"));
            return evidence;
        }

        MethodBrowserSurface prepareWriteBrowserSurface() throws Exception {
            registerWriteMethods(true); assertBrowserFixtureHasNoGlobalRole(); grantTrialPermissions(); grantWriteAcl();
            assertWriteCounts(0, 0);
            assertWriteCatalogPagination();
            Path auth = Files.createTempFile("bmapi-2e-browser-auth-", ".json"); closers.add(() -> Files.deleteIfExists(auth));
            Map<String, Object> cookie = new LinkedHashMap<>();
            cookie.put("name", PlatformSessionCookieService.SESSION_COOKIE_NAME); cookie.put("value", platformBearer);
            cookie.put("domain", "127.0.0.1"); cookie.put("path", PlatformSessionCookieService.SESSION_COOKIE_PATH);
            cookie.put("expires", java.time.Instant.now().plusSeconds(1_800).getEpochSecond());
            cookie.put("httpOnly", true); cookie.put("secure", false); cookie.put("sameSite", "Strict");
            Files.writeString(auth, json.writeValueAsString(Map.of("cookies", List.of(cookie), "origins", List.of())), StandardCharsets.UTF_8);
            return new MethodBrowserSurface(controlBridge.baseUrl(), "", projectId(), PROJECT_CODE,
                    StatefulWriteBusinessFixture.STORAGE_NAME, StatefulWriteBusinessFixture.QUALIFIED_NAME, auth.toAbsolutePath().toString());
        }

        void assertWriteCatalogPagination() throws Exception {
            var page = writeResponse(writeRequest("GET", "/api/business-methods?projectId=" + projectId()
                    + "&current=1&size=1", null));
            assertEquals(2, ((Number) page.get("total")).intValue());
            assertEquals(1, ((List<?>) page.get("records")).size());
        }

        /** H2 JSON exposes a quoted JSON scalar through JDBC, unlike the MySQL text contract used by these mappers. */
        private void normalizeH2JsonTextColumns() {
            for (String column : List.of("payload_json")) {
                database.jdbc().execute("ALTER TABLE capability_snapshot ALTER COLUMN " + column + " VARCHAR");
            }
            for (String column : List.of("field_diff_json", "impact_json", "before_state_json")) {
                database.jdbc().execute("ALTER TABLE capability_diff_item ALTER COLUMN " + column + " VARCHAR");
            }
        }

        private void registerAndSync() {
            starter.registerAndSync();
            assertTrue(Files.exists(credentialFile), "Starter must persist only its isolated issued credential");
            starter.scanAndSyncCapabilities();
        }

        private com.enterprise.ai.common.dto.ApiResult<CapabilityScannerClient.ManifestData> actualScannerManifest(
                CapabilityScannerClient.ScanRequest input, boolean controller) {
            var metadata = new ProjectMetadata(input.projectName(), input.baseUrl(), input.contextPath());
            var options = input.options() == null ? null : json.convertValue(input.options(),
                    com.enterprise.ai.text.tooling.scanner.ScanOptions.class);
            Path root = Path.of(input.scanPath());
            var manifest = controller ? new ControllerAnnotationToolManifestScanner().scan(root, metadata, options,
                    input.incrementalSinceEpochMs()) : new OpenApiToolManifestScanner().scan(root,
                    input.specFile() == null ? null : root.resolve(input.specFile()), metadata, options,
                    input.incrementalSinceEpochMs());
            // Match the normal scanner wire boundary: actual facts are serialized, not copied contracts.
            return com.enterprise.ai.common.dto.ApiResult.ok(json.copy()
                    .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .convertValue(manifest, CapabilityScannerClient.ManifestData.class));
        }

        WriteApiBrowserSurface prepareWriteApiBrowserSurface() throws Exception {
            return prepareWriteApiBrowserSurface("order-notes.yaml");
        }

        WriteApiBrowserSurface prepareWriteApiBrowserSurface(String sourceFixture) throws Exception {
            writeApiRoot = Files.createTempDirectory("bmapi-3b4-source-");
            Path spec = writeApiRoot.resolve("order-notes.yaml");
            Files.copy(Path.of("src/test/resources/bmapi3b4", sourceFixture), spec);
            closers.add(() -> { Files.deleteIfExists(spec); Files.deleteIfExists(writeApiRoot); });
            database.jdbc().update("INSERT INTO capability_scan_project "
                    + "(id,name,project_code,environment,project_kind,base_url,context_path,scan_path,scan_type,spec_file,status) "
                    + "VALUES (41,'Orders','orders','dev','SCAN','https://not-an-execution-origin.invalid','',?,'openapi','order-notes.yaml','created')",
                    writeApiRoot.toAbsolutePath().toString());
            var session = sessionUnchecked();
            Long roleId = database.jdbc().queryForObject("SELECT id FROM control_platform_role WHERE role_code='bmapi-fixture-role'", Long.class);
            var role = new PlatformUserRoleEntity(); role.setUserId(platformUserId); role.setRoleId(roleId);
            role.setScopeType("PROJECT"); role.setScopeValue("orders"); role.setCreatedAt(LocalDateTime.now());
            session.getMapper(PlatformUserRoleMapper.class).insert(role);
            grantTrialPermissions();
            var permission = new PlatformPermissionEntity(); permission.setPermissionCode(PlatformPermissions.PLATFORM_WRITE);
            permission.setPermissionName(PlatformPermissions.PLATFORM_WRITE); permission.setResourceType("PROJECT"); permission.setAction("ACCESS");
            session.getMapper(PlatformPermissionMapper.class).insert(permission);
            var grant = new PlatformRolePermissionEntity(); grant.setRoleId(roleId); grant.setPermissionId(permission.getId());
            session.getMapper(PlatformRolePermissionMapper.class).insert(grant);
            database.jdbc().update("UPDATE control_platform_login_session SET expires_at=? WHERE user_id=?",
                    LocalDateTime.now().plusMinutes(90), platformUserId);
            writeApiBridge = LoopbackBridge.start(MockMvcBuilders.standaloneSetup(writeApi).build()); closers.add(writeApiBridge);
            // Existing project credential is created through the real public API, never inserted into a credential row.
            var credential = multiSourcePublicRequest("POST", "/api/workflows/credentials", Map.of(
                    "name", "Orders isolated API key", "type", "API_KEY_HEADER", "scope", "PROJECT", "status", "ACTIVE",
                    "projectId", 41L, "projectCode", "orders", "secret", Map.of("headerName", "X-API-Key", "apiKey", StatefulWriteApiFixtureController.PROJECT_SECRET)));
            assertEquals(200, credential.statusCode(), "real project credential create");
            writeApiCredentialRef = requiredText(responseMap(credential), "credentialRef");
            writeApiCredentialId = ((Number) responseMap(credential).get("id")).longValue();
            Path auth = Files.createTempFile("bmapi-3b4-browser-auth-", ".json"); closers.add(() -> Files.deleteIfExists(auth));
            Files.writeString(auth, json.writeValueAsString(Map.of("cookies", List.of(Map.of(
                    "name", PlatformSessionCookieService.SESSION_COOKIE_NAME, "value", platformBearer, "domain", "127.0.0.1",
                    "path", PlatformSessionCookieService.SESSION_COOKIE_PATH, "expires", java.time.Instant.now().plusSeconds(5400).getEpochSecond(),
                    "httpOnly", true, "secure", false, "sameSite", "Strict")), "origins", List.of())), StandardCharsets.UTF_8);
            assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM capability_http_api_asset", Integer.class));
            assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_http_api_connection", Integer.class));
            return new WriteApiBrowserSurface(controlBridge.baseUrl(), writeApiRoot.toAbsolutePath().toString(),
                    "order-notes.yaml", writeApiBridge.baseUrl(), writeApiCredentialRef, auth.toAbsolutePath().toString());
        }

        HttpApiCatalogService.ApiDetail discoverWriteApi() throws Exception {
            assertEquals(200, multiSourcePublicRequest("POST", "/api/scan-projects/41/rescan", Map.of()).statusCode());
            var owner = httpApiCatalog.detail(1L); assertNotNull(owner); assertEquals("POST", owner.summary().httpMethod());
            assertEquals("WRITE", owner.contract().path("sideEffect").asText());
            return owner;
        }

        HttpApiCatalogService.ApiDetail writeApiOwner() { return httpApiCatalog.detail(1L); }

        void acceptWriteApiAndConnect() throws Exception {
            var owner = httpApiCatalog.detail(1L);
            assertEquals(200, multiSourcePublicRequest("POST", "/api/apis/1/accept", Map.of("expectedSourceSetRevision", owner.summary().sourceSetRevision())).statusCode());
            var connected = multiSourcePublicRequest("PUT", "/api/apis/1/connection", Map.of(
                    "origin", writeApiBridge.baseUrl(), "authMode", "API_KEY_HEADER", "credentialRef", writeApiCredentialRef));
            assertEquals(200, connected.statusCode(), "safe connection refusal: " + responseMap(connected).get("code")
                    + "; console policy: " + com.enterprise.ai.common.capability.HttpApiConsolePolicy.unsupportedReason(httpApiCatalog.detail(1L).acceptedContract()));
        }

        Map<String, Object> writeApiBody() throws Exception {
            var owner = httpApiCatalog.detail(1L);
            var connection = responseMap(multiSourcePublicRequest("GET", "/api/apis/1/connection", null));
            return new LinkedHashMap<>(Map.of("invocationId", UUID.randomUUID().toString(), "confirmedSideEffect", true,
                    "expectedContractHash", owner.summary().acceptedContractHash(), "expectedSourceSetRevision", owner.summary().sourceSetRevision(),
                    "connectionRevision", connection.get("revision"), "pathParams", Map.of("orderId", "ORD-3B4"), "queryParams", Map.of(),
                    "expectedCredentialRevision", connection.get("credentialRevision"),
                    "body", Map.of("note", "isolated-write", "apiKey", StatefulWriteApiFixtureController.SENSITIVE_INPUT, "notify", false)));
        }

        void setWriteApiAcl(boolean allowed) {
            database.jdbc().update("UPDATE control_tool_acl SET permission=? WHERE project_code='orders'", allowed ? "ALLOW" : "DENY");
        }
        void setWriteApiPermission(boolean allowed) {
            Long permissionId = database.jdbc().queryForObject("SELECT id FROM control_platform_permission WHERE permission_code=?",
                    Long.class, PlatformPermissions.CAPABILITY_INVOKE);
            Long roleId = database.jdbc().queryForObject("SELECT id FROM control_platform_role WHERE role_code='bmapi-fixture-role'", Long.class);
            database.jdbc().update("DELETE FROM control_platform_role_permission WHERE role_id=? AND permission_id=?", roleId, permissionId);
            if (allowed) {
                var grant = new PlatformRolePermissionEntity(); grant.setRoleId(roleId); grant.setPermissionId(permissionId);
                sessionUnchecked().getMapper(PlatformRolePermissionMapper.class).insert(grant);
            }
        }
        void dropNextWriteApiResponse() { writeApiBridge.dropNextResponseAfterEndpoint(); }
        void writeWorkflowResponseMode(String mode) { writeApi.responseMode.set(mode); }
        Map<String, Object> writeWorkflowActualBody() { return writeApi.lastBody.get(); }
        Map<String, Object> writeWorkflowBodyEvidence() {
            var result = new LinkedHashMap<String, Object>();
            result.put("rawBody", writeApi.lastRawBody.get());
            result.put("contentType", writeApi.lastContentType.get());
            result.put("counts", writeWorkflowCounts());
            return result;
        }
        Map<String, Object> writeWorkflowCounts() {
            return Map.of("writes", writeApi.noteCount(), "postHits", writeApi.postHits.get(),
                    "authenticatedHits", writeApi.authenticatedHits.get(), "redirectedHits", writeApi.redirectedHits.get(),
                    "consoleLedger", database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_console_capability_invocation WHERE target_type='HTTP_API'", Integer.class));
        }
        void writeWorkflowRemoveSensitiveDeclarationsDuringResponse() {
            writeApi.afterAppend.set(() -> {
                try { removeWriteApiSensitiveDeclarationsAndAccept(); }
                catch (Exception invalid) { throw new IllegalStateException("source change fixture failed", invalid); }
            });
        }

        void writeWorkflowAgentPlan(Map<String, Object> arguments) { writeWorkflowAgent.planAndRequestWrite(arguments); }
        void writeWorkflowAgentFinish() { writeWorkflowAgent.finishConfirmedWrite(); }
        void writeWorkflowAgentReplan(Map<String, Object> arguments) { writeWorkflowAgent.attemptFailedWriteReplan(arguments); }
        int writeWorkflowAgentModelCalls() { return writeWorkflowAgent.modelCalls(); }
        Map<String, Object> writeWorkflowAgentEvidence() {
            return Map.of("approvals", database.jdbc().queryForObject(
                            "SELECT COUNT(*) FROM runtime_interaction_session WHERE source_type='SUPERVISOR_POLICY'", Integer.class),
                    "confirmationRequired", database.jdbc().queryForObject(
                            "SELECT COUNT(*) FROM runtime_guard_decision_log WHERE decision='REQUIRE_CONFIRMATION'", Integer.class),
                    "allowed", database.jdbc().queryForObject(
                            "SELECT COUNT(*) FROM runtime_guard_decision_log WHERE decision='ALLOW'", Integer.class),
                    "globalRuntimeGrants", database.jdbc().queryForObject(
                            "SELECT COUNT(*) FROM control_platform_user_role ur JOIN control_platform_role_permission rp ON rp.role_id=ur.role_id "
                                    + "JOIN control_platform_permission p ON p.id=rp.permission_id WHERE ur.scope_type='GLOBAL' "
                                    + "AND (p.permission_code LIKE 'agent:%' OR p.permission_code LIKE 'workflow:%')", Integer.class));
        }

        long prepareWriteWorkflowMcp(String workflowId, String riskOverride) throws Exception {
            var session = sessionUnchecked();
            // Only MCP management is GLOBAL because that existing route requires it. No runtime/project grant is GLOBAL.
            var role = new PlatformRoleEntity(); role.setRoleCode("bmapi-3cd-mcp-management");
            role.setRoleName("Isolated MCP management"); role.setRoleKind("CUSTOM"); role.setStatus("ACTIVE");
            role.setCreatedAt(LocalDateTime.now()); role.setUpdatedAt(LocalDateTime.now());
            session.getMapper(PlatformRoleMapper.class).insert(role);
            for (String code : List.of(McpHubManagementAccess.READ, McpHubManagementAccess.MANAGE_PUBLICATIONS,
                    McpHubManagementAccess.MANAGE_CREDENTIALS, McpHubManagementAccess.MANAGE_CREDENTIAL_ROLES)) {
                Long existing = database.jdbc().query("SELECT id FROM control_platform_permission WHERE permission_code=?",
                        rs -> rs.next() ? rs.getLong(1) : null, code);
                if (existing == null) {
                    var permission = new PlatformPermissionEntity(); permission.setPermissionCode(code);
                    permission.setPermissionName(code); permission.setResourceType("MCP"); permission.setAction("ACCESS");
                    session.getMapper(PlatformPermissionMapper.class).insert(permission); existing = permission.getId();
                }
                var grant = new PlatformRolePermissionEntity(); grant.setRoleId(role.getId()); grant.setPermissionId(existing);
                session.getMapper(PlatformRolePermissionMapper.class).insert(grant);
            }
            var management = new PlatformUserRoleEntity(); management.setUserId(platformUserId); management.setRoleId(role.getId());
            management.setScopeType("GLOBAL"); management.setScopeValue("*"); management.setCreatedAt(LocalDateTime.now());
            session.getMapper(PlatformUserRoleMapper.class).insert(management);
            var allow = new ControlToolAclEntity(); allow.setRoleCode("bmapi-mcp-role"); allow.setProjectId(41L);
            allow.setProjectCode("orders"); allow.setTargetKind("TOOL"); allow.setTargetName(workflowId);
            allow.setPermission("ALLOW"); allow.setEnabled(true); allow.setNote("explicit isolated WRITE Workflow MCP authorization");
            allow.setCreatedAt(LocalDateTime.now()); allow.setUpdatedAt(LocalDateTime.now());
            session.getMapper(ControlToolAclMapper.class).insert(allow);
            var created = multiSourcePublicRequest("POST", "/api/mcp/publications", Map.of(
                    "name", "bmapi-3cd-write-publication", "description", "Isolated published POST representative flow"));
            assertEquals(201, created.statusCode()); changePublicationId = number(responseMap(created).get("id"));
            var itemBody = new LinkedHashMap<String, Object>(); itemBody.put("sourceKind", "WORKFLOW"); itemBody.put("sourceRef", workflowId);
            if (riskOverride != null) itemBody.put("riskLevelOverride", riskOverride);
            var item = multiSourcePublicRequest("POST", "/api/mcp/publications/" + changePublicationId + "/items", itemBody);
            assertEquals(201, item.statusCode());
            return changePublicationId;
        }

        HttpResponse<byte[]> publishWriteWorkflowMcp() throws Exception {
            return multiSourcePublicRequest("POST", "/api/mcp/publications/" + changePublicationId + "/publish",
                    Map.of("irreversibleAcknowledged", false));
        }

        void createWriteWorkflowMcpClient() throws Exception {
            var client = multiSourcePublicRequest("POST", "/api/mcp/publications/" + changePublicationId + "/clients", Map.of(
                    "name", "Isolated 3CD WRITE MCP client", "projectId", 41L, "projectCode", "orders", "environment", "dev",
                    "tenantId", "orders", "roles", List.of("bmapi-mcp-role"), "toolScope", List.of()));
            assertEquals(201, client.statusCode()); var data = responseMap(client);
            changeClientId = number(map(data.get("client"), "created private client").get("id"));
            String clientKey = requiredText(data, "plaintextApiKey");
            var listed = mcpRequest(controlBridge, clientKey,
                    Map.of("jsonrpc", "2.0", "id", "write-workflow-tools-list", "method", "tools/list"));
            var listedResult = map(listed.get("result"), "actual MCP tools/list result");
            assertInstanceOf(List.class, listedResult.get("tools"));
            var tools = (List<?>) listedResult.get("tools"); assertEquals(1, tools.size());
            String actualName = requiredText(map(tools.get(0), "fixed published Workflow tool"), "name");
            changeMcp = new McpSurface(controlBridge, clientKey, actualName);
        }

        Map<String, Object> callWriteWorkflowMcp(Map<String, Object> arguments) throws Exception {
            var called = mcpRequest(changeMcp.bridge, changeMcp.apiKey, Map.of("jsonrpc", "2.0", "id", "explicit-new-write-run",
                    "method", "tools/call", "params", Map.of("name", changeMcp.toolName, "arguments", arguments)), true);
            if (called.containsKey("error")) return Map.of("success", false, "code", "MCP_OUTER_POLICY_DENIED");
            var result = map(called.get("result"), "MCP result"); var structured = map(result.get("structuredContent"), "MCP structured result");
            var outcome = new LinkedHashMap<String, Object>(); outcome.put("success", Boolean.FALSE.equals(result.get("isError")));
            outcome.put("code", structured.getOrDefault("errorCode", "RUNTIME_HTTP_API_EXECUTED"));
            if (structured.containsKey("answer")) outcome.put("answer", structured.get("answer"));
            if (result.get("_meta") instanceof Map<?, ?> meta) {
                outcome.put("runId", meta.get("reachai/runId")); outcome.put("traceId", meta.get("reachai/traceId"));
            }
            return outcome;
        }

        Map<String, Object> writeWorkflowTrace(String traceId) throws Exception {
            var response = multiSourcePublicRequest("GET", "/api/runops/traces/" + traceId, null);
            assertEquals(200, response.statusCode()); return responseMap(response);
        }
        Map<String, Object> writeWorkflowReferences() throws Exception {
            var response = multiSourcePublicRequest("GET", "/api/apis/1/references", null);
            assertEquals(200, response.statusCode()); return responseMap(response);
        }
        void changeWriteApiSource() throws Exception {
            Path spec = writeApiRoot.resolve("order-notes.yaml");
            Files.writeString(spec, Files.readString(spec).replace("maxLength: 120", "maxLength: 121"), StandardCharsets.UTF_8);
            assertEquals(200, multiSourcePublicRequest("POST", "/api/scan-projects/41/rescan", Map.of()).statusCode());
        }
        void acceptWriteApiCandidate() throws Exception {
            var owner = httpApiCatalog.detail(1L);
            assertEquals(200, multiSourcePublicRequest("POST", "/api/apis/1/accept",
                    Map.of("expectedSourceSetRevision", owner.summary().sourceSetRevision())).statusCode());
        }
        void removeWriteWorkflowApiSource() throws Exception {
            Files.writeString(writeApiRoot.resolve("order-notes.yaml"),
                    "openapi: 3.0.3\ninfo:\n  title: Isolated empty source\n  version: 1.0.0\npaths: {}\n", StandardCharsets.UTF_8);
            assertEquals(200, multiSourcePublicRequest("POST", "/api/scan-projects/41/rescan", Map.of()).statusCode());
        }
        void restoreWriteWorkflowApiSource() throws Exception {
            Files.writeString(writeApiRoot.resolve("order-notes.yaml"),
                    Files.readString(Path.of("src/test/resources/bmapi3b4/order-notes-workflow.yaml"), StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            discoverWriteApi(); acceptWriteApiCandidate();
        }
        void restoreWriteWorkflowCredential() throws Exception {
            assertEquals(200, runtimeRequest("PUT", "/api/workflows/credentials/" + writeApiCredentialId, json.writeValueAsBytes(Map.of(
                    "name", "Orders explicitly restored API key", "type", "API_KEY_HEADER", "scope", "PROJECT", "status", "ACTIVE", "projectId", 41L,
                    "projectCode", "orders", "secret", Map.of("headerName", "X-API-Key", "apiKey", StatefulWriteApiFixtureController.PROJECT_SECRET))), Map.of()).statusCode());
        }
        void writeWorkflowMcpClientScope(String project) {
            database.jdbc().update("UPDATE control_mcp_client SET project_id=?,project_code=? WHERE id=?", 999L, project, changeClientId);
            var allow = new ControlToolAclEntity(); allow.setRoleCode("bmapi-mcp-role"); allow.setProjectId(999L);
            allow.setProjectCode(project); allow.setTargetKind("TOOL"); allow.setTargetName("*");
            allow.setPermission("ALLOW"); allow.setEnabled(true); allow.setCreatedAt(LocalDateTime.now()); allow.setUpdatedAt(LocalDateTime.now());
            sessionUnchecked().getMapper(ControlToolAclMapper.class).insert(allow);
        }
        void writeWorkflowMcpClientEnabled(boolean enabled) {
            database.jdbc().update("UPDATE control_mcp_client SET enabled=? WHERE id=?", enabled, changeClientId);
        }
        void writeWorkflowMcpAcl(boolean enabled) {
            database.jdbc().update("UPDATE control_tool_acl SET enabled=? WHERE role_code='bmapi-mcp-role'", enabled);
        }
        Map<String, Object> writeWorkflowFixedVersionEvidence(String workflowId) {
            return Map.of("mcp", database.jdbc().queryForList("SELECT tools_snapshot_json FROM control_mcp_publication_revision WHERE publication_id=? ORDER BY id", changePublicationId),
                    "agent", database.jdbc().queryForList("SELECT workflow_version_id,read_only,risk_level FROM runtime_agent_workflow_tool WHERE workflow_id=?", workflowId));
        }
        Map<String, Object> writeWorkflowPublishedEvidence(String workflowId) throws Exception {
            var working = responseMap(multiSourcePublicRequest("GET", "/api/workflows/" + workflowId + "/working-copy", null));
            return Map.of("workingCopy", working,
                    "versions", database.jdbc().queryForList("SELECT id,workflow_id,version,status,snapshot_json,published_by FROM runtime_workflow_version WHERE workflow_id=? ORDER BY id", workflowId),
                    "pins", database.jdbc().queryForList("SELECT id,workflow_id,workflow_version_id,node_id,api_id,qualified_name,accepted_contract_hash,source_set_revision,connection_revision,credential_revision FROM runtime_workflow_http_api_pin WHERE workflow_id=? ORDER BY id", workflowId),
                    "counts", writeWorkflowCounts());
        }
        void removeWriteApiSensitiveDeclarationsAndAccept() throws Exception {
            Path spec = writeApiRoot.resolve("order-notes.yaml");
            Files.writeString(spec, Files.readString(spec, StandardCharsets.UTF_8)
                    .replace(", format: password", "").replace(", writeOnly: true", ""), StandardCharsets.UTF_8);
            discoverWriteApi();
            var owner = httpApiCatalog.detail(1L);
            assertEquals(200, multiSourcePublicRequest("POST", "/api/apis/1/accept",
                    Map.of("expectedSourceSetRevision", owner.summary().sourceSetRevision())).statusCode());
        }
        void changeWriteApiConnection() throws Exception {
            var connection = responseMap(multiSourcePublicRequest("GET", "/api/apis/1/connection", null));
            assertEquals(200, multiSourcePublicRequest("PUT", "/api/apis/1/connection", Map.of("origin", writeApiBridge.baseUrl(),
                    "authMode", "API_KEY_HEADER", "credentialRef", writeApiCredentialRef, "expectedRevision", connection.get("revision"))).statusCode());
        }
        void changeWriteApiCredential() throws Exception {
            // Simulate a privileged concurrent edit through Runtime's actual credential API.
            // Control currently reserves credential update for global managers; do not broaden that policy for this batch.
            assertEquals(200, runtimeRequest("PUT", "/api/workflows/credentials/" + writeApiCredentialId, json.writeValueAsBytes(Map.of(
                    "name", "Orders revised API key", "type", "API_KEY_HEADER", "scope", "PROJECT", "status", "ACTIVE", "projectId", 41L,
                    "projectCode", "orders", "secret", Map.of("headerName", "X-API-Key", "apiKey", "synthetic-3b4-revised-key"))), Map.of()).statusCode());
        }
        void assertPublishedWriteApiStillRejectsStudioDebug() throws Exception {
            var detail = httpApiCatalog.detail(1L);
            var owner = httpApiConnections.owner(new com.enterprise.ai.common.capability.HttpApiConsoleContracts.ConnectionCommand(
                    1, 1L, detail.summary().qualifiedName(), 41L, "orders", "dev", null));
            assertTrue(owner.consoleCallSupported()); assertFalse(owner.firstCallSupported());
            var graph = json.readTree(controllerApiBrowserGraph("graphSpecJson"));
            String previous = graph.path("nodes").get(0).path("ref").path("qualifiedName").asText();
            var draft = createSavedControllerApiDraft(json.writeValueAsString(graph).replace(previous, detail.summary().qualifiedName()));
            var readOnly = trialRequest(Map.of("workflowId", draft.workflowId(), "expectedRevision", draft.revision(),
                    "inputParams", Map.of("orderId", "ORD-3B4")));
            assertEquals(409, readOnly.statusCode()); assertEquals("HTTP_API_TRIAL_READ_ONLY_REQUIRED", responseMap(readOnly).get("errorCode"));
            var debug = multiSourcePublicRequest("POST", "/api/workflows/studio/debug-run", Map.of("workflowId", draft.workflowId(),
                    "inputParams", Map.of("orderId", "ORD-3B4"), "debugOptions", Map.of("consoleInvocation", true, "confirmedSideEffect", true)));
            assertEquals(200, debug.statusCode()); assertEquals(false, responseMap(debug).get("success"));
            assertEquals("HTTP_API_PUBLISHED_PIN_INVALID", responseMap(debug).get("errorCode"));
            var validation = multiSourcePublicRequest("POST", "/api/workflows/" + draft.workflowId() + "/versions/validate", Map.of());
            assertEquals(200, validation.statusCode()); // This endpoint validates graph structure, not owner execution support.
            var release = multiSourcePublicRequest("POST", "/api/workflows/" + draft.workflowId() + "/versions/publish",
                    Map.of("version", "write-published-debug", "baseRevision", draft.revision(), "rolloutPercent", 100));
            assertEquals(200, release.statusCode(), () -> runtimeBridge.lastResponseSummary());
            assertEquals("WRITE", json.readTree(String.valueOf(responseMap(release).get("snapshotJson"))).path("httpApiRiskFloor").asText());
            assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_version WHERE workflow_id=?", Integer.class, draft.workflowId()));
            assertWriteApiCounts(0, 0);
        }
        void assertWriteApiCounts(int writes, int ledger) {
            assertEquals(writes, writeApi.noteCount(), "isolated business note count");
            assertEquals(writes, writeApi.postHits.get(), "actual upstream POST hits");
            assertEquals(writes, writeApi.authenticatedHits.get(), "project credential used on every actual call");
            assertEquals(0, writeApi.redirectedHits.get(), "no redirected write");
            assertEquals(ledger, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_console_capability_invocation WHERE target_type='HTTP_API'", Integer.class));
        }
        void assertWriteApiAuditSafe() throws Exception {
            for (String table : List.of("runtime_console_capability_invocation", "runtime_run", "runtime_trace_span", "runtime_tool_call_log")) {
                String persisted = json.writeValueAsString(database.jdbc().queryForList("SELECT * FROM " + table));
                assertFalse(persisted.contains(StatefulWriteApiFixtureController.SENSITIVE_INPUT), "sensitive body must not persist in " + table);
                assertFalse(persisted.contains(StatefulWriteApiFixtureController.PROJECT_SECRET), "project credential must not persist in " + table);
                assertFalse(persisted.contains("synthetic-3cd-format-secret"), "password-format input must not persist in " + table);
                assertFalse(persisted.contains("synthetic-3cd-writeonly-secret"), "write-only input must not persist in " + table);
            }
        }
        Map<String, Boolean> writeApiSensitiveAuditLeaks(List<String> sensitiveValues) throws Exception {
            var leaks = new LinkedHashMap<String, Boolean>();
            for (String table : List.of("runtime_console_capability_invocation", "runtime_run", "runtime_trace_span", "runtime_tool_call_log")) {
                String persisted = json.writeValueAsString(database.jdbc().queryForList("SELECT * FROM " + table));
                leaks.put(table, sensitiveValues.stream().anyMatch(persisted::contains));
            }
            return leaks;
        }
        Map<String, Object> writeApiEvidence() throws Exception {
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("projectId", 41L); evidence.put("projectCode", "orders"); evidence.put("environment", "dev");
            evidence.put("assets", database.jdbc().queryForObject("SELECT COUNT(*) FROM capability_http_api_asset", Integer.class));
            evidence.put("connections", database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_http_api_connection", Integer.class));
            evidence.put("upstreamRequests", writeApiBridge.requestCount()); evidence.put("postHits", writeApi.postHits.get());
            evidence.put("noteCount", writeApi.noteCount()); evidence.put("projectCredentialHits", writeApi.authenticatedHits.get());
            evidence.put("redirectedHits", writeApi.redirectedHits.get());
            evidence.put("publicPostRequests", controlBridge.requestsFor("POST", "/api/apis/1/invocations"));
            evidence.put("publicQueryRequests", controlBridge.requestsForPrefix("GET", "/api/api-invocations/"));
            evidence.put("ledger", database.jdbc().queryForList("SELECT invocation_id,status,dispatch_stage,side_effect,confirmed_side_effect,platform_actor_id,project_id,project_code,environment,run_id,trace_id FROM runtime_console_capability_invocation WHERE target_type='HTTP_API' ORDER BY created_at,id"));
            evidence.put("runs", database.jdbc().queryForList("SELECT id,trace_id,run_type,status,project_code,metadata_json FROM runtime_run"));
            evidence.put("traceRoots", database.jdbc().queryForList("SELECT trace_id,span_type,status,metadata_json FROM runtime_trace_span WHERE span_type='CONSOLE_CAPABILITY'"));
            assertWriteApiAuditSafe(); return evidence;
        }

        MultiSourceBrowserSurface prepareMultiSourceBrowserSurface() throws Exception {
            return prepareMultiSourceBrowserSurface("BMAPI 3B3 API Workflow", "bmapi-3b3-api-workflow");
        }

        MultiSourceBrowserSurface prepareRetirementBrowserSurface() throws Exception {
            registerAndSync();
            database.addInterceptor(new com.enterprise.ai.capability.config.CapabilityMybatisPlusConfiguration()
                    .capabilityMybatisPlusInterceptor());
            var surface = prepareMultiSourceBrowserSurface("BMAPI 5A normal owner Workflow", "bmapi-5a-owner-workflow");
            grantExpectedMultiSourceBrowserAcl();
            var allow = new ControlToolAclEntity(); allow.setRoleCode("bmapi-fixture-role");
            allow.setProjectId(projectId()); allow.setProjectCode(PROJECT_CODE); allow.setTargetKind("TOOL");
            allow.setTargetName("bmapi2d:queryOrder"); allow.setPermission("ALLOW"); allow.setEnabled(true);
            allow.setNote("isolated explicit BMAPI-5A query Console authorization");
            allow.setCreatedAt(LocalDateTime.now()); allow.setUpdatedAt(LocalDateTime.now());
            sessionUnchecked().getMapper(ControlToolAclMapper.class).insert(allow);
            return surface;
        }

        int retiredEntryRequestCount() {
            return capabilityBridge.routeRequests.entrySet().stream().filter(entry -> entry.getKey().matches(
                    "(?:POST|PUT) .*?(?:promote-to-tool|push-to-global-tool|unpromote-from-global|promote-by-module|/test|/toggle)$")
                    || entry.getKey().matches("PUT /api/scan-projects/\\d+/scan-tools/\\d+$"))
                    .mapToInt(entry -> entry.getValue().get()).sum();
        }

        Map<String, List<Map<String, Object>>> readOnlySourceSnapshot() {
            Map<String, List<Map<String, Object>>> facts = new LinkedHashMap<>();
            for (String table : List.of("capability_scan_project_tool", "capability_tool_definition",
                    "capability_http_api_asset", "capability_http_api_source_binding",
                    "capability_http_api_inventory_state", "capability_http_api_inventory_member",
                    "capability_http_api_acceptance", "runtime_console_capability_invocation",
                    "runtime_workflow_version", "runtime_workflow_http_api_pin")) {
                facts.put(table, database.jdbc().queryForList("SELECT * FROM " + table + " ORDER BY id"));
            }
            return facts;
        }

        int readOnlyReconcileRequestCount() {
            return capabilityBridge.requestsFor("POST", "/api/scan-projects/41/tools/reconcile");
        }

        Map<String, Object> retirementBrowserEvidence() throws Exception {
            var facts = new LinkedHashMap<>(multiSourceEvidence());
            facts.put("sdkProjectId", projectId()); facts.put("sdkProjectCode", PROJECT_CODE);
            facts.put("sdkMethodCalls", capabilityHits.get()); facts.put("retiredEntryRequests", retiredEntryRequestCount());
            facts.put("toolProjectionCount", database.jdbc().queryForObject("SELECT COUNT(*) FROM capability_tool_definition", Integer.class));
            facts.put("versions", database.jdbc().queryForList("SELECT id,version,status,graph_spec_snapshot_json FROM runtime_workflow_version ORDER BY id"));
            facts.put("pins", database.jdbc().queryForList("SELECT api_id,qualified_name,accepted_contract_hash FROM runtime_workflow_http_api_pin ORDER BY id"));
            facts.put("references", publicApiReferences());
            return facts;
        }

        Map<String, Object> verifyRetirementBrowserTrial() throws Exception {
            assertMultiSourceWorkflowSelection();
            assertEquals(1, capabilityHits.get(), "one real query Console call");
            assertEquals(Map.of("orderNo", "A-5A", "customerId", "customer-5A"), invocationEvidence.dtoInput.get());
            assertEquals(2, controllerOrder.methodCalls(), "one API Console call and one explicit saved-draft trial");
            var runs = database.jdbc().queryForList("SELECT id,trace_id,status FROM runtime_run WHERE workflow_id=? AND entry_type='STUDIO_READ_ONLY_TRIAL'", multiSourceWorkflowId);
            assertEquals(1, runs.size()); assertEquals("COMPLETED", runs.get(0).get("status"));
            assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_version", Integer.class));
            assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_http_api_pin", Integer.class));
            assertEquals(3, database.jdbc().queryForObject("SELECT COUNT(*) FROM capability_tool_definition", Integer.class));
            assertEquals(0, retiredEntryRequestCount());
            assertPublicRunOps(new McpInvocation("", String.valueOf(runs.get(0).get("id")), String.valueOf(runs.get(0).get("trace_id"))));
            return retirementBrowserEvidence();
        }

        Map<String, Object> completeRetirementBrowserPublication() throws Exception {
            Long versionId = database.jdbc().queryForObject("SELECT id FROM runtime_workflow_version WHERE workflow_id=? AND status='ACTIVE'", Long.class, multiSourceWorkflowId);
            var version = workflowVersions.selectById(versionId);
            var release = new WorkflowRelease(multiSourceWorkflowId, versionId, version.getVersion(), version.getGraphSpecSnapshotJson());
            assertEquals("1.0.0", release.version());
            var mcp = publishMcpWorkflow(release, "orders", 41L, "bmapi-5a-owner-workflow");
            var invocation = callMcpWorkflow(mcp, Map.of("orderId", "O-5A", "detailLevel", "full"));
            assertControllerApiReleaseAndTrace(release, invocation); assertPublicRunOps(invocation);
            assertEquals(3, controllerOrder.methodCalls()); assertEquals(0, retiredEntryRequestCount());
            var facts = new LinkedHashMap<>(retirementBrowserEvidence());
            facts.put("publishedCallRunId", invocation.runId()); facts.put("publishedCallTraceId", invocation.traceId());
            return facts;
        }

        private MultiSourceBrowserSurface prepareMultiSourceBrowserSurface(String name, String keySlug) throws Exception {
            return prepareMultiSourceBrowserSurface(name, keySlug, true);
        }

        private MultiSourceBrowserSurface prepareMultiSourceBrowserSurface(String name, String keySlug,
                                                                          boolean seedEditableDraft) throws Exception {
            multiSourceRoot = Files.createTempDirectory("bmapi-3b3-sources-");
            Path controller = multiSourceRoot.resolve("ControllerOrderFixtureController.java");
            Files.copy(Path.of("src/test/java/com/enterprise/ai/runtime/runops/consolehttpapi/ControllerOrderFixtureController.java"), controller);
            Files.copy(Path.of("src/test/resources/bmapi3b3/orders.yaml"), multiSourceRoot.resolve("orders.yaml"));
            closers.add(() -> { Files.deleteIfExists(controller); Files.deleteIfExists(multiSourceRoot.resolve("orders.yaml"));
                Files.deleteIfExists(multiSourceRoot); });
            database.jdbc().update("INSERT INTO capability_scan_project "
                    + "(id,name,project_code,environment,project_kind,base_url,context_path,scan_path,scan_type,status) "
                    + "VALUES (41,'Orders','orders','dev','SCAN','https://not-an-execution-origin.invalid','',?,'controller','created')",
                    multiSourceRoot.toAbsolutePath().toString());
            var session = sessionUnchecked();
            var role = new PlatformUserRoleEntity(); role.setUserId(platformUserId);
            role.setRoleId(database.jdbc().queryForObject(
                    "SELECT id FROM control_platform_role WHERE role_code = 'bmapi-fixture-role'", Long.class));
            role.setScopeType("PROJECT"); role.setScopeValue("orders"); role.setCreatedAt(LocalDateTime.now());
            session.getMapper(PlatformUserRoleMapper.class).insert(role);
            grantTrialPermissions();
            // This representative flow explicitly includes accept/connection writes; discovery grants no ACL.
            var writePermission = new PlatformPermissionEntity();
            writePermission.setPermissionCode(PlatformPermissions.PLATFORM_WRITE);
            writePermission.setPermissionName(PlatformPermissions.PLATFORM_WRITE);
            writePermission.setResourceType("PROJECT"); writePermission.setAction("ACCESS");
            session.getMapper(PlatformPermissionMapper.class).insert(writePermission);
            var writeGrant = new PlatformRolePermissionEntity();
            writeGrant.setRoleId(role.getRoleId()); writeGrant.setPermissionId(writePermission.getId());
            session.getMapper(PlatformRolePermissionMapper.class).insert(writeGrant);
            database.jdbc().update("UPDATE control_platform_login_session SET expires_at=? WHERE user_id=?",
                    LocalDateTime.now().plusMinutes(90), platformUserId);
            controllerOrderBridge = LoopbackBridge.start(MockMvcBuilders.standaloneSetup(controllerOrder).build());
            closers.add(controllerOrderBridge);
            // Selection-based flows need an editable draft. The market creator owns its own draft.
            if (seedEditableDraft) {
                var graph = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(controllerApiBrowserGraph("graphSpecJson"));
                var node = (com.fasterxml.jackson.databind.node.ObjectNode) graph.path("nodes").get(0);
                node.remove("ref"); node.set("config", json.createObjectNode()); node.put("name", "Choose API");
                Map<String, Object> create = new LinkedHashMap<>();
                create.put("projectId", 41L); create.put("projectCode", "orders"); create.put("name", name);
                create.put("keySlug", keySlug); create.put("workflowKind", "GENERAL");
                create.put("executionEngine", "GRAPH_SPEC"); create.put("graphSpecJson", json.writeValueAsString(graph));
                create.put("canvasJson", controllerApiBrowserGraph("canvasJson"));
                create.put("inputSchemaJson", "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"},\"detailLevel\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}");
                create.put("outputSchemaJson", "{\"type\":\"object\"}"); create.put("status", "DRAFT");
                create.put("definitionAuthority", "USER"); create.put("creationChannel", "STUDIO");
                var created = runtimeRequest("POST", "/api/workflows", json.writeValueAsBytes(create), Map.of());
                assertEquals(200, created.statusCode(), runtimeBridge.lastResponseSummary());
                multiSourceWorkflowId = requiredText(responseMap(created), "id");
            }
            Path auth = Files.createTempFile("bmapi-3b3-browser-auth-", ".json");
            closers.add(() -> Files.deleteIfExists(auth));
            Files.writeString(auth, json.writeValueAsString(Map.of("cookies", List.of(Map.of(
                    "name", PlatformSessionCookieService.SESSION_COOKIE_NAME, "value", platformBearer,
                    "domain", "127.0.0.1", "path", PlatformSessionCookieService.SESSION_COOKIE_PATH,
                    "expires", java.time.Instant.now().plusSeconds(5_400).getEpochSecond(),
                    "httpOnly", true, "secure", false, "sameSite", "Strict")), "origins", List.of())), StandardCharsets.UTF_8);
            assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM capability_http_api_asset", Integer.class));
            return new MultiSourceBrowserSurface(controlBridge.baseUrl(), multiSourceWorkflowId,
                    multiSourceRoot.toAbsolutePath().toString(), "orders.yaml", controllerOrderBridge.baseUrl(),
                    auth.toAbsolutePath().toString());
        }

        HttpResponse<byte[]> multiSourcePublicRequest(String method, String path, Map<String, Object> body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(controlBridge.baseUrl() + path)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + platformBearer).header("Accept", "application/json");
            if (body != null) request.header("Content-Type", "application/json");
            return HttpClient.newHttpClient().send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        MultiSourceBrowserSurface prepareMarketSurface() throws Exception {
            return prepareMarketSurface(true);
        }

        MultiSourceBrowserSurface prepareMarketBrowserSurface() throws Exception {
            return prepareMarketSurface(false);
        }

        private MultiSourceBrowserSurface prepareMarketSurface(boolean seedEditableDraft) throws Exception {
            // Use the existing production paging plugin; the catalog's real total is part of browser evidence.
            database.addInterceptor(new com.enterprise.ai.capability.config.CapabilityMybatisPlusConfiguration()
                    .capabilityMybatisPlusInterceptor());
            var surface = prepareMultiSourceBrowserSurface("BMAPI 3D market API Workflow", "bmapi-3d-market-workflow", seedEditableDraft);
            database.jdbc().update("INSERT INTO capability_external_api_source (id,source_key,name,source_type,trust_level,status) "
                    + "VALUES (11,'bmapi-3d-official','Isolated official catalog','OFFICIAL','HIGH','ACTIVE')");
            database.jdbc().update("INSERT INTO capability_external_api_provider (id,provider_key,name,status) "
                    + "VALUES (11,'bmapi-3d-provider','Isolated catalog provider','ACTIVE')");
            String requestSchema = "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\",\"location\":\"path\"}},\"required\":[\"orderId\"],\"additionalProperties\":false}";
            String responseSchema = "{\"type\":\"object\",\"properties\":{\"state\":{\"type\":\"string\"},\"orderId\":{\"type\":\"string\"}}}";
            for (int offset = 0; offset < 2; offset++) {
                long entryId = 11 + offset, versionId = 21 + offset, operationId = 31 + offset;
                String entryKey = offset == 0 ? "isolated-orders-alpha" : "isolated-orders-beta";
                database.jdbc().update("INSERT INTO capability_external_api_entry "
                                + "(id,entry_key,provider_id,source_id,title,summary,category_code,auth_type,pricing_type,https_supported,publication_status,verification_status,spec_status,docs_url) "
                                + "VALUES (?,?,11,11,?,'Read-only isolated order API','BUSINESS','NONE','FREE',1,'PUBLISHED','UNVERIFIED','VALID','https://catalog-docs.invalid/orders')",
                        entryId, entryKey, entryKey);
                database.jdbc().update("INSERT INTO capability_external_api_version "
                                + "(id,entry_id,version_key,base_url,spec_hash,publication_status,published_at) VALUES (?,?,'v1',?,?,'PUBLISHED',?)",
                        versionId, entryId, controllerOrderBridge.baseUrl(), "a".repeat(64), LocalDateTime.now());
                database.jdbc().update("INSERT INTO capability_external_api_operation "
                                + "(id,version_id,operation_key,operation_id,title,http_method,path,side_effect,auth_required,request_schema_json,response_schema_json,response_content_type,response_status,status) "
                                + "VALUES (?,?,'get-order','getOrder','Order status','GET','/orders/{orderId}','READ_ONLY',0,?,?,'application/json',200,'ACTIVE')",
                        operationId, versionId, requestSchema, responseSchema);
            }
            assertBrowserFixtureHasNoGlobalRole();
            return surface;
        }

        Map<String, Object> marketEvidence() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("assets", database.jdbc().queryForList("SELECT id,qualified_name,project_code,environment,http_method,route_template,status,accepted_contract_hash FROM capability_http_api_asset ORDER BY id"));
            out.put("bindings", database.jdbc().queryForList("SELECT id,asset_id,source_kind,source_key,source_revision,status FROM capability_http_api_source_binding ORDER BY id"));
            out.put("integrations", database.jdbc().queryForList("SELECT id,project_id,project_code,entry_id,version_id,environment,status,selection_revision FROM capability_project_external_api ORDER BY id"));
            out.put("pins", database.jdbc().queryForList("SELECT id,workflow_id,workflow_version_id,api_id,qualified_name,accepted_contract_hash,source_set_revision,connection_revision,credential_revision FROM runtime_workflow_http_api_pin ORDER BY id"));
            out.put("apiConsoleRuns", database.jdbc().queryForList("SELECT id,run_type,status,trace_id,snapshot_json FROM runtime_run WHERE run_type='CONSOLE_HTTP_API' ORDER BY id"));
            out.put("upstreamRequests", controllerOrderBridge.requestCount());
            return out;
        }

        HttpApiCatalogService.ApiDetail marketDetail(long id) { return httpApiCatalog.detail(id); }

        Map<String, Object> marketConnection(long id) throws Exception {
            var response = multiSourcePublicRequest("GET", "/api/apis/" + id + "/connection", null);
            assertEquals(200, response.statusCode()); return responseMap(response);
        }

        Map<String, Object> marketInvocationBody(long id) throws Exception {
            var owner = marketDetail(id); var connection = marketConnection(id);
            Map<String, Object> body = new LinkedHashMap<>(); body.put("invocationId", UUID.randomUUID().toString());
            body.put("expectedContractHash", owner.summary().acceptedContractHash());
            body.put("expectedSourceSetRevision", owner.summary().sourceSetRevision());
            body.put("connectionRevision", connection.get("revision"));
            body.put("expectedCredentialRevision", connection.get("credentialRevision"));
            body.put("pathParams", Map.of("orderId", "O-3D")); body.put("queryParams", Map.of());
            return body;
        }

        void marketSaveDraft(long apiId) throws Exception {
            marketSaveDraft(apiId, null);
        }

        void marketSaveDraft(long apiId, String authorKey) throws Exception {
            var working = changeWorkingCopy();
            var graph = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(controllerApiBrowserGraph("graphSpecJson"));
            var api = (com.fasterxml.jackson.databind.node.ObjectNode) graph.path("nodes").get(0);
            var owner = marketDetail(apiId);
            ((com.fasterxml.jackson.databind.node.ObjectNode)api.path("ref")).put("qualifiedName", owner.summary().qualifiedName());
            ((com.fasterxml.jackson.databind.node.ObjectNode)api.path("config")).put("httpApiAssetId", apiId);
            ((com.fasterxml.jackson.databind.node.ObjectNode)api.path("config").path("inputMapping")).remove("queryParams.detailLevel");
            ((com.fasterxml.jackson.databind.node.ObjectNode)api.path("config").path("toolConfig")).put("qualifiedName", owner.summary().qualifiedName());
            ((com.fasterxml.jackson.databind.node.ObjectNode)api.path("config").path("toolConfig")).put("ref", owner.summary().qualifiedName());
            ((com.fasterxml.jackson.databind.node.ObjectNode)api.path("config").path("toolConfig").path("inputMapping")).remove("queryParams.detailLevel");
            Map<String, Object> save = new LinkedHashMap<>();
            for (String key : List.of("name", "keySlug", "description", "workflowKind", "executionEngine", "inputSchemaJson", "outputSchemaJson", "extraJson")) save.put(key, working.get(key));
            if (authorKey != null) save.put("keySlug", authorKey);
            save.put("graphSpecJson", json.writeValueAsString(graph)); save.put("canvasJson", controllerApiBrowserGraph("canvasJson"));
            save.put("baseRevision", working.get("revision"));
            var saved = multiSourcePublicRequest("PUT", "/api/workflows/" + multiSourceWorkflowId + "/working-copy", save);
            assertEquals(200, saved.statusCode(), "normal market API draft save");
            if (authorKey != null) assertEquals(authorKey, changeWorkingCopy().get("keySlug"));
            assertFalse(json.writeValueAsString(graph).contains("marketRef"));
            assertFalse(json.writeValueAsString(graph).contains(controllerOrderBridge.baseUrl()));
        }

        Map<String, Object> marketTrial() throws Exception {
            var response = multiSourcePublicRequest("POST", "/api/workflows/studio/read-only-trials", Map.of(
                    "workflowId", multiSourceWorkflowId, "expectedRevision", changeWorkingCopy().get("revision"),
                    "inputParams", Map.of("orderId", "O-3D")));
            return Map.of("httpStatus", response.statusCode(), "body", responseMap(response));
        }

        Map<String, Object> marketValidate() throws Exception {
            var response = multiSourcePublicRequest("POST", "/api/workflows/" + multiSourceWorkflowId + "/versions/validate", Map.of());
            assertEquals(200, response.statusCode()); return responseMap(response);
        }

        Map<String, Object> marketPublishAttempt(String version) throws Exception {
            var response = multiSourcePublicRequest("POST", "/api/workflows/" + multiSourceWorkflowId + "/versions/publish", Map.of(
                    "version", version, "rolloutPercent", 100, "note", "explicit isolated market release",
                    "baseRevision", changeWorkingCopy().get("revision")));
            return Map.of("httpStatus", response.statusCode(), "body", responseMap(response));
        }

        void marketSaveRawDraft(String placement) throws Exception {
            var working = changeWorkingCopy();
            var graph = (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(controllerApiBrowserGraph("graphSpecJson"));
            var api = (com.fasterxml.jackson.databind.node.ObjectNode)graph.path("nodes").get(0);
            api.put("type", "HTTP_REQUEST"); api.remove("ref");
            var config = json.createObjectNode(); config.put("url", controllerOrderBridge.baseUrl() + "/orders/O-3D");
            config.put("method", "GET"); config.put("bodyType", "none"); config.put("outputAlias", "api_output");
            if ("nested".equals(placement)) config.set("httpConfig", json.createObjectNode().put("url", controllerOrderBridge.baseUrl() + "/orders/O-3D").putNull("marketRef"));
            else if (!"ordinary".equals(placement)) config.putNull("marketRef");
            api.set("config", config);
            Map<String, Object> save = new LinkedHashMap<>();
            for (String key : List.of("name", "keySlug", "workflowKind", "executionEngine", "inputSchemaJson", "outputSchemaJson")) save.put(key, working.get(key));
            save.put("baseRevision", working.get("revision")); save.put("graphSpecJson", json.writeValueAsString(graph));
            save.put("canvasJson", controllerApiBrowserGraph("canvasJson"));
            assertEquals(200, multiSourcePublicRequest("PUT", "/api/workflows/" + multiSourceWorkflowId + "/working-copy", save).statusCode(), "legacy WIP remains saveable");
        }

        Map<String, Object> marketRawDebug() throws Exception {
            var working = changeWorkingCopy();
            var response = multiSourcePublicRequest("POST", "/api/workflows/studio/debug-run", Map.of("workflowId", multiSourceWorkflowId,
                    "graphSpecJson", working.get("graphSpecJson"), "inputParams", Map.of("orderId", "O-3D")));
            var body = responseMap(response);
            if ("BMAPI_TEST_BRIDGE_FAILURE".equals(body.get("code"))) return Map.of("httpStatus", response.statusCode(), "body", body,
                    "controlDiagnostic", controlBridge.lastResponseSummary(), "runtimeDiagnostic", runtimeBridge.lastResponseSummary());
            return Map.of("httpStatus", response.statusCode(), "body", body);
        }

        Map<String, Object> marketCallPublished() throws Exception {
            var version = database.jdbc().queryForMap("SELECT id,version,graph_spec_snapshot_json FROM runtime_workflow_version WHERE workflow_id=? AND status='ACTIVE'", multiSourceWorkflowId);
            var release = new WorkflowRelease(multiSourceWorkflowId, number(version.get("id")), String.valueOf(version.get("version")), String.valueOf(version.get("graph_spec_snapshot_json")));
            // Isolated MCP setup uses the existing owning services; no GLOBAL platform role is granted.
            String authorKey = requiredText(changeWorkingCopy(), "keySlug");
            changeMcp = publishMcpWorkflow(release, "orders", 41L, authorKey);
            var invocation = callMcpWorkflow(changeMcp, Map.of("orderId", "O-3D"));
            assertTrue(invocation.answer().contains("PAID"));
            assertPublicRunOps(invocation); assertBrowserFixtureHasNoGlobalRole();
            return Map.of("runId", invocation.runId(), "traceId", invocation.traceId(), "versionId", release.versionId(), "answer", invocation.answer());
        }

        String marketRejectPublished() throws Exception {
            int before = controllerOrderBridge.requestCount();
            String code = callMcpWorkflowError(changeMcp, Map.of("orderId", "O-3D"));
            assertTrue(code.startsWith("HTTP_API_"), code); assertEquals(before, controllerOrderBridge.requestCount()); return code;
        }

        void marketCatalogMutation(String mutation) {
            switch (mutation) {
                case "new-version" -> {
                    database.jdbc().update("INSERT INTO capability_external_api_version (id,entry_id,version_key,base_url,spec_hash,publication_status,published_at) SELECT 41,entry_id,'v2',base_url,?,'PUBLISHED',? FROM capability_external_api_version WHERE id=21", "b".repeat(64), LocalDateTime.now());
                    database.jdbc().update("INSERT INTO capability_external_api_operation (id,version_id,operation_key,operation_id,title,http_method,path,side_effect,auth_required,request_schema_json,response_schema_json,response_content_type,response_status,status) SELECT 41,41,operation_key,operation_id,title,http_method,path,side_effect,auth_required,request_schema_json,response_schema_json,response_content_type,response_status,status FROM capability_external_api_operation WHERE id=31");
                }
                case "entry-off" -> database.jdbc().update("UPDATE capability_external_api_entry SET publication_status='DRAFT' WHERE id=11");
                case "entry-on" -> database.jdbc().update("UPDATE capability_external_api_entry SET publication_status='PUBLISHED' WHERE id=11");
                case "version-off" -> database.jdbc().update("UPDATE capability_external_api_version SET publication_status='DRAFT' WHERE id=21");
                case "version-on" -> database.jdbc().update("UPDATE capability_external_api_version SET publication_status='PUBLISHED' WHERE id=21");
                case "operation-off" -> database.jdbc().update("UPDATE capability_external_api_operation SET status='REMOVED' WHERE id=31");
                case "operation-on" -> database.jdbc().update("UPDATE capability_external_api_operation SET status='ACTIVE' WHERE id=31");
                case "auth-required" -> database.jdbc().update("UPDATE capability_external_api_operation SET auth_required=1 WHERE id=31");
                case "auth-none" -> database.jdbc().update("UPDATE capability_external_api_operation SET auth_required=0 WHERE id=31");
                case "write-method" -> database.jdbc().update("UPDATE capability_external_api_operation SET http_method='POST',side_effect='WRITE' WHERE id=31");
                case "read-method" -> database.jdbc().update("UPDATE capability_external_api_operation SET http_method='GET',side_effect='READ_ONLY' WHERE id=31");
                case "missing-media" -> database.jdbc().update("UPDATE capability_external_api_operation SET response_content_type=NULL WHERE id=31");
                case "media-on" -> database.jdbc().update("UPDATE capability_external_api_operation SET response_content_type='application/json' WHERE id=31");
                case "missing-status" -> database.jdbc().update("UPDATE capability_external_api_operation SET response_status=NULL WHERE id=31");
                case "status-on" -> database.jdbc().update("UPDATE capability_external_api_operation SET response_status=200 WHERE id=31");
                case "unsupported-schema" -> database.jdbc().update("UPDATE capability_external_api_operation SET request_schema_json=? WHERE id=31", "{\"type\":\"object\",\"oneOf\":[]}");
                case "wrong-project-role" -> database.jdbc().update("UPDATE control_platform_user_role SET scope_value='blocked' WHERE user_id=? AND scope_type='PROJECT' AND scope_value='orders'", platformUserId);
                case "restore-project-role" -> database.jdbc().update("UPDATE control_platform_user_role SET scope_value='orders' WHERE user_id=? AND scope_type='PROJECT' AND scope_value='blocked'", platformUserId);
                default -> throw new IllegalArgumentException("unsupported isolated catalog mutation");
            }
        }

        void marketDropNextUpstream() { controllerOrderBridge.dropNextResponseAfterEndpoint(); }
        void marketFailUpstream(boolean fail) { controllerOrder.requireDetailLevel(fail); }

        String marketBrowserAdoptCreatedWorkflow() throws Exception {
            var rows = database.jdbc().queryForList("SELECT id FROM runtime_workflow WHERE project_id=41 AND key_slug LIKE 'api-isolated-orders-alpha-get-order-%'");
            assertEquals(1, rows.size(), "browser must create a new real Workflow from the fixed owner API");
            multiSourceWorkflowId = String.valueOf(rows.get(0).get("id"));
            var working = changeWorkingCopy(); assertEquals("DRAFT", working.get("status"));
            var graph = json.readTree(String.valueOf(working.get("graphSpecJson")));
            assertEquals(1L, graph.at("/nodes/0/config/httpApiAssetId").asLong());
            assertEquals(marketDetail(1).summary().qualifiedName(), graph.at("/nodes/0/ref/qualifiedName").asText());
            assertEquals("params.orderId", graph.at("/nodes/0/config/inputMapping/pathParams.orderId").asText());
            assertEquals("nodeOutput.api-node.state", graph.at("/nodes/1/config/assignments/api_result").asText());
            assertEquals("variable_output", graph.at("/nodes/1/config/outputAlias").asText(),
                    "node output must not overwrite the scalar assignment target api_result");
            assertFalse(String.valueOf(working.get("graphSpecJson")).contains("marketRef"));
            assertFalse(String.valueOf(working.get("graphSpecJson")).contains(controllerOrderBridge.baseUrl()));
            assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_version WHERE workflow_id=?", Integer.class, multiSourceWorkflowId));
            return multiSourceWorkflowId;
        }

        byte[] marketReadOnlyTrialResponse() { return controlBridge.lastReadOnlyTrialResponse(); }

        void marketCreateAuthorDraft() throws Exception {
            String qualifiedName = marketDetail(1).summary().qualifiedName();
            Map<String, Object> graph = Map.of("schemaVersion", 2,
                    "nodes", List.of(
                            Map.of("id", "api-node", "type", "TOOL", "name", "GET /orders/{orderId}",
                                    "ref", Map.of("kind", "TOOL", "name", qualifiedName, "qualifiedName", qualifiedName, "projectCode", "orders"),
                                    "config", Map.of("configVersion", 2, "httpApiAssetId", 1L,
                                            "inputMapping", Map.of("pathParams.orderId", "params.orderId"), "outputAlias", "api_output")),
                            Map.of("id", "api-variable", "type", "VARIABLE_ASSIGN", "name", "API result variable",
                                    "config", Map.of("assignments", Map.of("api_result", "nodeOutput.api-node.state"), "outputAlias", "variable_output"))),
                    "edges", List.of(Map.of("id", "api-to-variable", "from", "api-node", "to", "api-variable", "condition", "always")),
                    "entryNodeId", "api-node", "exitNodeIds", List.of("api-variable"));
            Map<String, Object> create = new LinkedHashMap<>();
            create.put("projectId", 41L); create.put("projectCode", "orders"); create.put("name", "Market author API Workflow");
            create.put("keySlug", "api-isolated-orders-alpha-get-order-http-author"); create.put("workflowKind", "GENERAL");
            create.put("executionEngine", "GRAPH_SPEC"); create.put("graphSpecJson", json.writeValueAsString(graph));
            create.put("canvasJson", "{\"schemaVersion\":1,\"layoutVersion\":1,\"nodes\":[],\"edges\":[]}");
            create.put("inputSchemaJson", "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"],\"additionalProperties\":false}");
            create.put("outputSchemaJson", "{\"type\":\"object\"}"); create.put("status", "DRAFT");
            create.put("definitionAuthority", "USER"); create.put("creationChannel", "STUDIO");
            assertEquals(200, multiSourcePublicRequest("POST", "/api/workflows", create).statusCode());
            marketBrowserAdoptCreatedWorkflow();
        }

        Map<String, Object> marketBrowserTrialCompleted() throws Exception {
            // Observe the actual safe Control HTTP response, not an invented business payload in Trace.
            var response = json.readTree(controlBridge.lastReadOnlyTrialResponse());
            assertTrue(response.path("success").asBoolean());
            assertEquals("PAID", response.at("/apiOutput/state").asText());
            assertTrue(response.at("/variables/api_result").isTextual(), "assignment must remain a scalar after output-alias writes");
            assertEquals("PAID", response.at("/variables/api_result").asText());
            var rows = database.jdbc().queryForList("SELECT id,trace_id,status FROM runtime_run WHERE workflow_id=? AND entry_type='STUDIO_READ_ONLY_TRIAL'", multiSourceWorkflowId);
            assertEquals(1, rows.size()); var run = rows.get(0); assertEquals("COMPLETED", run.get("status"));
            String traceId = String.valueOf(run.get("trace_id"));
            var metadata = json.readTree(database.jdbc().queryForObject("SELECT metadata_json FROM runtime_trace_span WHERE trace_id=? AND span_type='WORKFLOW'", String.class, traceId));
            assertEquals(String.valueOf(platformUserId), metadata.path("platformActorId").asText());
            assertEquals(1L, metadata.path("apiId").asLong());
            assertEquals(marketDetail(1).summary().qualifiedName(), metadata.path("apiQualifiedName").asText());
            assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_version WHERE workflow_id=?", Integer.class, multiSourceWorkflowId));
            assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_http_api_pin WHERE workflow_id=?", Integer.class, multiSourceWorkflowId));
            assertPublicRunOps(new McpInvocation("", String.valueOf(run.get("id")), traceId));
            return Map.of("runId", run.get("id"), "traceId", traceId, "draftRevision", metadata.path("draftRevision").asText(),
                    "apiState", response.at("/apiOutput/state").asText(), "assignedScalar", response.at("/variables/api_result").asText(),
                    "variableNode", marketBrowserVariableTrace(traceId));
        }

        private Map<String, Object> marketBrowserVariableTrace(String traceId) {
            var spans = database.jdbc().queryForList("SELECT status,output_summary FROM runtime_trace_span WHERE trace_id=? AND node_id='api-variable'", traceId);
            assertEquals(1, spans.size()); var span = spans.get(0); assertEquals("SUCCESS", span.get("status"));
            return span;
        }

        Map<String, Object> marketBrowserPublished() throws Exception {
            var working = changeWorkingCopy();
            var rows = database.jdbc().queryForList("SELECT id,published_by FROM runtime_workflow_version WHERE workflow_id=? AND status='ACTIVE'", multiSourceWorkflowId);
            assertEquals(1, rows.size()); assertEquals("platform:" + platformUserId, rows.get(0).get("published_by"));
            var call = marketCallPublished();
            assertFalse(String.valueOf(working.get("graphSpecJson")).contains(controllerOrderBridge.baseUrl()));
            assertEquals(3, controllerOrderBridge.requestCount(), "browser Console, browser trial and actual published call");
            var references = responseMap(multiSourcePublicRequest("GET", "/api/apis/1/references", null));
            assertEquals("COMPLETE", references.get("runtimeEvidence")); assertTrue(json.writeValueAsString(references).contains("PUBLISHED"));
            return Map.of("call", call, "references", references, "persistence", marketEvidence(),
                    "variableNode", marketBrowserVariableTrace(String.valueOf(call.get("traceId"))));
        }

        void selectMultiSourceOpenApi() throws Exception {
            var updated = multiSourcePublicRequest("PUT", "/api/scan-projects/41", Map.of(
                    "name", "Orders", "projectCode", "orders", "environment", "dev", "projectKind", "SCAN",
                    "baseUrl", "https://not-an-execution-origin.invalid", "contextPath", "", "scanPath",
                    multiSourceRoot.toAbsolutePath().toString(), "scanType", "openapi", "specFile", "orders.yaml"));
            assertEquals(200, updated.statusCode(), new String(updated.body(), StandardCharsets.UTF_8));
        }

        HttpResponse<byte[]> saveMultiSourceConnection() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>(); body.put("origin", controllerOrderBridge.baseUrl());
            body.put("authMode", "NONE"); body.put("credentialRef", null); body.put("expectedRevision", null);
            return multiSourcePublicRequest("PUT", "/api/apis/1/connection", body);
        }

        Map<String, Object> multiSourceInvocationBody(String accepted) {
            return Map.of("invocationId", UUID.randomUUID().toString(), "expectedContractHash", accepted,
                    "connectionRevision", 1L, "pathParams", Map.of("orderId", "O-3B3"),
                    "queryParams", Map.of("detailLevel", "full"));
        }

        void grantExpectedMultiSourceBrowserAcl() {
            var project = sessionUnchecked().getMapper(ScanProjectMapper.class).selectById(41L);
            var manifest = new ControllerAnnotationToolManifestScanner().scan(multiSourceRoot,
                    new ProjectMetadata("orders", project.getBaseUrl(), ""));
            var assets = new HttpApiAssetService(sessionUnchecked().getMapper(HttpApiAssetMapper.class),
                    sessionUnchecked().getMapper(HttpApiSourceBindingMapper.class), new HttpApiContractCanonicalizer(json));
            var plan = new ControllerScanHttpApiIntakeService(assets,
                    sessionUnchecked().getMapper(HttpApiSourceBindingMapper.class)).prepare(project,
                    manifest.httpApis().stream().map(Fixture::scannerData).toList(), manifest.httpApiInventoryComplete());
            String target = assets.validate(plan.operations().get(0).request()).qualifiedName();
            var allow = new ControlToolAclEntity(); allow.setRoleCode("bmapi-fixture-role"); allow.setProjectId(41L);
            allow.setProjectCode("orders"); allow.setTargetKind("TOOL"); allow.setTargetName(target);
            allow.setPermission("ALLOW"); allow.setEnabled(true); allow.setNote("explicit isolated browser authorization");
            allow.setCreatedAt(LocalDateTime.now()); allow.setUpdatedAt(LocalDateTime.now());
            sessionUnchecked().getMapper(ControlToolAclMapper.class).insert(allow);
        }

        HttpApiCatalogService.ApiDetail multiSourceDetail() { return httpApiCatalog.detail(1L); }

        MultiSourceBrowserSurface prepareChangeImpactSurface() throws Exception {
            var surface = prepareMultiSourceBrowserSurface("BMAPI 4A API Workflow", "bmapi-4a-api-workflow");
            grantExpectedMultiSourceBrowserAcl();
            var session = sessionUnchecked();
            // The existing MCP management API is global. This synthetic role contains only MCP permissions;
            // all project/runtime permissions remain scoped to the fixture projects.
            var role = new PlatformRoleEntity(); role.setRoleCode("bmapi-4a-mcp-management");
            role.setRoleName("Isolated MCP management"); role.setRoleKind("CUSTOM"); role.setStatus("ACTIVE");
            role.setCreatedAt(LocalDateTime.now()); role.setUpdatedAt(LocalDateTime.now());
            session.getMapper(PlatformRoleMapper.class).insert(role);
            for (String code : List.of(McpHubManagementAccess.READ, McpHubManagementAccess.MANAGE_PUBLICATIONS,
                    McpHubManagementAccess.MANAGE_CREDENTIALS, McpHubManagementAccess.MANAGE_CREDENTIAL_ROLES)) {
                var permission = new PlatformPermissionEntity(); permission.setPermissionCode(code);
                permission.setPermissionName(code); permission.setResourceType("MCP"); permission.setAction("ACCESS");
                session.getMapper(PlatformPermissionMapper.class).insert(permission);
                var grant = new PlatformRolePermissionEntity(); grant.setRoleId(role.getId()); grant.setPermissionId(permission.getId());
                session.getMapper(PlatformRolePermissionMapper.class).insert(grant);
            }
            var global = new PlatformUserRoleEntity(); global.setUserId(platformUserId); global.setRoleId(role.getId());
            global.setScopeType("GLOBAL"); global.setScopeValue("*"); global.setCreatedAt(LocalDateTime.now());
            session.getMapper(PlatformUserRoleMapper.class).insert(global);
            var allow = new ControlToolAclEntity(); allow.setRoleCode("bmapi-mcp-role"); allow.setProjectId(41L);
            allow.setProjectCode("orders"); allow.setTargetKind("TOOL"); allow.setTargetName(multiSourceWorkflowId);
            allow.setPermission("ALLOW"); allow.setEnabled(true); allow.setNote("explicit isolated Workflow MCP authorization");
            allow.setCreatedAt(LocalDateTime.now()); allow.setUpdatedAt(LocalDateTime.now());
            session.getMapper(ControlToolAclMapper.class).insert(allow);
            changeAclCount = database.jdbc().queryForObject("SELECT COUNT(*) FROM control_tool_acl", Integer.class);
            var created = multiSourcePublicRequest("POST", "/api/mcp/publications", Map.of(
                    "name", "bmapi-4a-api-publication", "description", "Isolated change-impact representative flow"));
            assertEquals(201, created.statusCode(), "normal MCP publication creation");
            changePublicationId = number(responseMap(created).get("id"));
            var item = multiSourcePublicRequest("POST", "/api/mcp/publications/" + changePublicationId + "/items",
                    Map.of("sourceKind", "WORKFLOW", "sourceRef", multiSourceWorkflowId, "riskLevelOverride", "READ"));
            assertEquals(201, item.statusCode(), "normal MCP item creation");
            return surface;
        }

        Long changePublicationId() { return changePublicationId; }

        HttpResponse<byte[]> changeScan(String kind) throws Exception {
            var updated = multiSourcePublicRequest("PUT", "/api/scan-projects/41", Map.of(
                    "name", "Orders", "projectCode", "orders", "environment", "dev", "projectKind", "SCAN",
                    "baseUrl", "https://not-an-execution-origin.invalid", "contextPath", "", "scanPath",
                    multiSourceRoot.toAbsolutePath().toString(), "scanType", kind, "specFile", "orders.yaml"));
            assertEquals(200, updated.statusCode(), "normal source selection");
            return multiSourcePublicRequest("POST", "/api/scan-projects/41/rescan", Map.of());
        }

        void changeSources(String phase) throws Exception {
            String yaml = Files.readString(Path.of("src/test/resources/bmapi3b3/orders.yaml"));
            String java = Files.readString(Path.of("src/test/java/com/enterprise/ai/runtime/runops/consolehttpapi/ControllerOrderFixtureController.java"));
            if (!"display".equals(phase)) {
                yaml = yaml.replace("in: query, required: false", "in: query, required: true");
                java = java.replace("required = false", "required = true");
            }
            controllerOrder.requireDetailLevel(!"display".equals(phase));
            switch (phase) {
                case "display" -> yaml = yaml.replace("title: Orders", "title: Orders display revision")
                        .replace("get:", "get:\n      summary: Updated display title only");
                case "required", "restore" -> { }
                case "partial" -> yaml = yaml.replace("get:", "get:\n      security: [{UnavailableScheme: []}]");
                case "failed" -> yaml = "openapi: [unterminated\n";
                case "one-removed" -> yaml = "openapi: 3.0.3\ninfo: {title: Orders, version: '2'}\npaths: {}\n";
                case "all-removed" -> {
                    yaml = "openapi: 3.0.3\ninfo: {title: Orders, version: '2'}\npaths: {}\n";
                    java = java.replace("@GetMapping(path = \"/{orderId}\", produces = \"application/json\")", "");
                }
                case "identity" -> { yaml = yaml.replace("/orders/{orderId}", "/orders-v2/{orderId}");
                    java = java.replace("@RequestMapping(\"/orders\")", "@RequestMapping(\"/orders-v2\")"); }
                default -> throw new IllegalArgumentException("unsupported change-impact phase");
            }
            Files.writeString(multiSourceRoot.resolve("orders.yaml"), yaml, StandardCharsets.UTF_8);
            Files.writeString(multiSourceRoot.resolve("ControllerOrderFixtureController.java"), java, StandardCharsets.UTF_8);
        }

        Map<String, Object> changeWorkingCopy() throws Exception {
            var response = multiSourcePublicRequest("GET", "/api/workflows/" + multiSourceWorkflowId + "/working-copy", null);
            assertEquals(200, response.statusCode()); return responseMap(response);
        }

        void changeSaveDraft(boolean mapRequiredQuery) throws Exception {
            var working = changeWorkingCopy();
            var graph = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(controllerApiBrowserGraph("graphSpecJson"));
            var api = (com.fasterxml.jackson.databind.node.ObjectNode) graph.path("nodes").get(0);
            ((com.fasterxml.jackson.databind.node.ObjectNode) api.path("ref")).put("qualifiedName", multiSourceDetail().summary().qualifiedName());
            var mappings = (com.fasterxml.jackson.databind.node.ObjectNode) api.path("config").path("inputMapping");
            if (mapRequiredQuery) mappings.put("queryParams.detailLevel", "params.detailLevel");
            else mappings.remove("queryParams.detailLevel");
            Map<String, Object> save = new LinkedHashMap<>();
            for (String key : List.of("name", "keySlug", "description", "workflowKind", "executionEngine",
                    "inputSchemaJson", "outputSchemaJson", "extraJson")) save.put(key, working.get(key));
            save.put("graphSpecJson", json.writeValueAsString(graph)); save.put("canvasJson", controllerApiBrowserGraph("canvasJson"));
            save.put("baseRevision", working.get("revision"));
            var response = multiSourcePublicRequest("PUT", "/api/workflows/" + multiSourceWorkflowId + "/working-copy", save);
            assertEquals(200, response.statusCode(), "normal draft save: " + new String(response.body(), StandardCharsets.UTF_8));
        }

        Map<String, Object> changePublishVersion(String version) throws Exception {
            var validate = multiSourcePublicRequest("POST", "/api/workflows/" + multiSourceWorkflowId + "/versions/validate", Map.of());
            assertEquals(200, validate.statusCode()); assertEquals(Boolean.TRUE, responseMap(validate).get("valid"));
            var published = multiSourcePublicRequest("POST", "/api/workflows/" + multiSourceWorkflowId + "/versions/publish",
                    Map.of("version", version, "rolloutPercent", 100, "note", "explicit API change-impact release",
                            "baseRevision", changeWorkingCopy().get("revision")));
            assertEquals(200, published.statusCode(), "normal Workflow publication: " + new String(published.body(), StandardCharsets.UTF_8));
            return responseMap(published);
        }

        void changePublishMcp() throws Exception {
            var response = multiSourcePublicRequest("POST", "/api/mcp/publications/" + changePublicationId + "/publish",
                    Map.of("irreversibleAcknowledged", false));
            assertEquals(200, response.statusCode(), "normal MCP publication: " + new String(response.body(), StandardCharsets.UTF_8));
        }

        Map<String, Object> changeInitialPublished() throws Exception {
            changeOriginalVersionId = database.jdbc().queryForObject(
                    "SELECT id FROM runtime_workflow_version WHERE workflow_id=? AND status='ACTIVE'", Long.class, multiSourceWorkflowId);
            changeOriginalSnapshot = workflowVersions.selectById(changeOriginalVersionId).getGraphSpecSnapshotJson();
            changeOriginalPins = json.writeValueAsString(database.jdbc().queryForList(
                    "SELECT * FROM runtime_workflow_http_api_pin WHERE workflow_version_id=?", changeOriginalVersionId));
            changeOriginalMcpRevisionId = database.jdbc().queryForObject(
                    "SELECT current_revision_id FROM control_mcp_publication WHERE id=?", Long.class, changePublicationId);
            assertNotNull(changeOriginalMcpRevisionId, "browser must explicitly publish the MCP surface");
            var client = multiSourcePublicRequest("POST", "/api/mcp/publications/" + changePublicationId + "/clients",
                    Map.of("name", "Isolated 4A MCP client", "projectId", 41L, "projectCode", "orders",
                            "environment", "dev", "tenantId", "orders", "roles", List.of("bmapi-mcp-role"), "toolScope", List.of()));
            assertEquals(201, client.statusCode(), "normal private MCP client setup");
            var data = responseMap(client); changeClientId = number(map(data.get("client"), "created private client").get("id"));
            changeMcp = new McpSurface(controlBridge, requiredText(data, "plaintextApiKey"), "bmapi-4a-api-workflow");
            return changeCall();
        }

        Map<String, Object> changeCall() throws Exception {
            var invocation = callMcpWorkflow(changeMcp, Map.of("orderId", "O-4A", "detailLevel", "full"));
            assertTrue(invocation.answer().contains("PAID")); assertTrue(invocation.answer().contains("order_state"));
            assertPublicRunOps(invocation);
            return Map.of("answer", invocation.answer(), "runId", invocation.runId(), "traceId", invocation.traceId(),
                    "workflowVersionId", database.jdbc().queryForObject("SELECT workflow_version_id FROM runtime_run WHERE trace_id=?",
                            Long.class, invocation.traceId()));
        }

        String changeRejectPublished() throws Exception {
            int before = controllerOrderBridge.requestCount();
            String code = callMcpWorkflowError(changeMcp, Map.of("orderId", "O-4A", "detailLevel", "full"));
            assertTrue(code.startsWith("HTTP_API_"), code); assertEquals(before, controllerOrderBridge.requestCount());
            return code;
        }

        String changeRejectOriginalAfterUpdate() {
            int before = controllerOrderBridge.requestCount();
            // Replay the original frozen binding through the production signed HTTP gateway, not the active version selector.
            var gateway = new TrustedMcpRuntimeExecutionGateway(new InternalServiceAuthSigner(INTERNAL_SECRET), json,
                    new McpHubProperties(), runtimeBridge.baseUrl());
            var outcome = gateway.execute(new com.enterprise.ai.control.mcp.application.port.McpRuntimeExecutionGateway.ToolExecutionCommand(
                    "WORKFLOW", multiSourceWorkflowId, changeOriginalVersionId, "bmapi-4a-api-workflow",
                    Map.of("orderId", "O-4A", "detailLevel", "full"), changeClientId, "Isolated 4A MCP client",
                    41L, "orders", "dev", "orders", changePublicationId, 1));
            assertFalse(outcome.success()); assertTrue(outcome.code().startsWith("HTTP_API_"), outcome.code());
            assertEquals(before, controllerOrderBridge.requestCount()); return outcome.code();
        }

        void changeAssertOriginalUntouched(boolean mcpUnchanged) throws Exception {
            assertEquals(changeOriginalSnapshot, workflowVersions.selectById(changeOriginalVersionId).getGraphSpecSnapshotJson());
            assertEquals(changeOriginalPins, json.writeValueAsString(database.jdbc().queryForList(
                    "SELECT * FROM runtime_workflow_http_api_pin WHERE workflow_version_id=?", changeOriginalVersionId)));
            assertEquals(changeAclCount, database.jdbc().queryForObject("SELECT COUNT(*) FROM control_tool_acl", Integer.class));
            if (mcpUnchanged) assertEquals(changeOriginalMcpRevisionId, database.jdbc().queryForObject(
                    "SELECT current_revision_id FROM control_mcp_publication WHERE id=?", Long.class, changePublicationId));
        }

        Map<String, Object> changeReferences() throws Exception { return publicApiReferences(); }
        void changeReferenceUnavailable(boolean value) { changeReferenceUnavailable = value; }

        Map<String, Object> changeVerifyReferenceIsolation() throws Exception {
            var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                    controlBridge.baseUrl() + "/api/apis/1/references")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(401, anonymous.statusCode());
            try {
                database.jdbc().update("UPDATE control_platform_user_role SET scope_value='blocked' WHERE user_id=? AND scope_type='PROJECT' AND scope_value='orders'", platformUserId);
                var denied = multiSourcePublicRequest("GET", "/api/apis/1/references", null);
                assertEquals(403, denied.statusCode(), "MCP management permissions cannot authorize another project's API references");
                return Map.of("anonymousStatus", anonymous.statusCode(), "wrongProjectStatus", denied.statusCode());
            } finally {
                database.jdbc().update("UPDATE control_platform_user_role SET scope_value='orders' WHERE user_id=? AND scope_type='PROJECT' AND scope_value='blocked'", platformUserId);
            }
        }

        Map<String, Object> changeRejectConsoleAndDraft() throws Exception {
            int before = controllerOrderBridge.requestCount();
            var console = multiSourcePublicRequest("POST", "/api/apis/1/invocations", multiSourceInvocationBody(multiSourceDetail().summary().acceptedContractHash()));
            assertEquals(409, console.statusCode());
            assertEquals("HTTP_API_CONTRACT_CHANGED", responseMap(console).get("code"));
            Long currentMcpRevision = database.jdbc().queryForObject(
                    "SELECT current_revision_id FROM control_mcp_publication WHERE id=?", Long.class, changePublicationId);
            var draft = changeSavedSourceGuardDraft();
            var trial = trialRequest(Map.of("workflowId", changeSourceGuardDraftId, "expectedRevision", draft.get("revision"),
                    "inputParams", Map.of("orderId", "O-4A", "detailLevel", "full")));
            assertEquals(409, trial.statusCode(), "source absence rejects draft trial");
            assertEquals("HTTP_API_TRIAL_SOURCE_CHANGED", responseMap(trial).get("errorCode"),
                    "SAVED_DRAFT_SOURCE_GUARD_NOT_PROVEN: a published-state precondition is not the source guard");
            assertEquals(before, controllerOrderBridge.requestCount());
            changeAssertOriginalUntouched(false);
            assertEquals(currentMcpRevision, database.jdbc().queryForObject(
                    "SELECT current_revision_id FROM control_mcp_publication WHERE id=?", Long.class, changePublicationId));
            return Map.of("consoleStatus", console.statusCode(), "console", responseMap(console), "draftStatus", trial.statusCode(),
                    "draft", responseMap(trial), "upstreamRequests", before, "savedDraft",
                    Map.of("workflowId", changeSourceGuardDraftId, "status", draft.get("status"), "revision", draft.get("revision"),
                            "nodeId", "api-node", "apiId", 1L, "qualifiedName", multiSourceDetail().summary().qualifiedName()));
        }

        private Map<String, Object> changeSavedSourceGuardDraft() throws Exception {
            if (changeSourceGuardDraftId == null) {
                // Editing a published Workflow does not turn it into an unpublished DRAFT. Create a real
                // never-published draft through normal Control/Runtime APIs, not a database status patch.
                var working = changeWorkingCopy();
                Map<String, Object> create = new LinkedHashMap<>();
                for (String key : List.of("workflowKind", "executionEngine", "inputSchemaJson", "outputSchemaJson",
                        "graphSpecJson", "canvasJson")) create.put(key, working.get(key));
                create.put("name", "BMAPI 4A missing-source draft"); create.put("keySlug", "bmapi-4a-source-guard-draft");
                create.put("projectId", 41L); create.put("projectCode", "orders");
                var response = multiSourcePublicRequest("POST", "/api/workflows", create);
                assertEquals(200, response.statusCode(), "normal unpublished Workflow creation");
                changeSourceGuardDraftId = requiredText(responseMap(response), "id");
            }
            String path = "/api/workflows/" + changeSourceGuardDraftId + "/working-copy";
            var loaded = multiSourcePublicRequest("GET", path, null); assertEquals(200, loaded.statusCode());
            var working = responseMap(loaded); assertEquals("DRAFT", working.get("status"));
            Map<String, Object> save = new LinkedHashMap<>();
            for (String key : List.of("name", "keySlug", "workflowKind", "executionEngine", "inputSchemaJson", "outputSchemaJson",
                    "graphSpecJson", "canvasJson")) save.put(key, working.get(key));
            save.put("baseRevision", working.get("revision"));
            var saved = multiSourcePublicRequest("PUT", path, save); assertEquals(200, saved.statusCode(), "normal draft save");
            var readBack = multiSourcePublicRequest("GET", path, null); assertEquals(200, readBack.statusCode());
            var draft = responseMap(readBack); assertEquals("DRAFT", draft.get("status"));
            assertEquals(responseMap(saved).get("revision"), draft.get("revision"));
            var graph = json.readTree(requiredText(draft, "graphSpecJson"));
            assertEquals(1L, graph.at("/nodes/0/config/httpApiAssetId").asLong());
            assertEquals(multiSourceDetail().summary().qualifiedName(), graph.at("/nodes/0/ref/qualifiedName").asText());
            assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_version WHERE workflow_id=?",
                    Integer.class, changeSourceGuardDraftId));
            return draft;
        }

        Map<String, Object> changeEvidence() throws Exception {
            Map<String, Object> facts = new LinkedHashMap<>(multiSourceEvidence());
            facts.put("publicationId", changePublicationId);
            facts.put("versions", database.jdbc().queryForList("SELECT id,version,status,graph_spec_snapshot_json,published_by FROM runtime_workflow_version WHERE workflow_id=? ORDER BY id", multiSourceWorkflowId));
            facts.put("pins", database.jdbc().queryForList("SELECT id,workflow_version_id,api_id,qualified_name,accepted_contract_hash,source_set_revision,node_id FROM runtime_workflow_http_api_pin WHERE workflow_id=? ORDER BY id", multiSourceWorkflowId));
            facts.put("mcpRevisions", database.jdbc().queryForList("SELECT id,revision_no,tools_snapshot_json FROM control_mcp_publication_revision WHERE publication_id=? ORDER BY id", changePublicationId));
            facts.put("references", publicApiReferences());
            facts.put("aclCount", database.jdbc().queryForObject("SELECT COUNT(*) FROM control_tool_acl", Integer.class));
            return facts;
        }

        void changeAssertIdentityNotMigrated() throws Exception {
            assertEquals(2, database.jdbc().queryForObject("SELECT COUNT(*) FROM capability_http_api_asset", Integer.class));
            var old = multiSourceDetail(); var replacement = httpApiCatalog.detail(2L);
            assertEquals("SOURCE_MISSING", old.summary().sourceStatus());
            assertEquals("DISCOVERED", replacement.summary().sourceStatus()); assertNull(replacement.summary().acceptedContractHash());
            assertNotEquals(old.summary().qualifiedName(), replacement.summary().qualifiedName());
            assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM control_tool_acl WHERE target_name=?", Integer.class,
                    replacement.summary().qualifiedName()));
            assertTrue(String.valueOf(changeReferences().get("references")).contains("api-node"));
            assertEquals(1L, json.readTree(String.valueOf(changeWorkingCopy().get("graphSpecJson"))).at("/nodes/0/config/httpApiAssetId").asLong());
            changeAssertOriginalUntouched(false);
        }

        void assertMultiSourceWorkflowSelection() throws Exception {
            var workflow = responseMap(runtimeRequest("GET", "/api/workflows/" + multiSourceWorkflowId + "/working-copy", null, Map.of()));
            var graph = json.readTree(requiredText(workflow, "graphSpecJson"));
            var node = graph.path("nodes").get(0);
            assertEquals(1L, node.at("/config/httpApiAssetId").asLong());
            assertEquals(httpApiCatalog.detail(1L).summary().qualifiedName(), node.at("/ref/qualifiedName").asText());
        }

        void changeMultiSourceSpec(String change) throws Exception {
            Path spec = multiSourceRoot.resolve("orders.yaml");
            String original = Files.readString(Path.of("src/test/resources/bmapi3b3/orders.yaml"));
            switch (change) {
                case "conflict" -> Files.writeString(spec, original.replace("state: {type: string}", "state: {type: integer}"));
                case "unknown" -> Files.writeString(spec, original.replace("get:", "get:\n      security: [{UnavailableScheme: []}]"));
                case "removed" -> Files.writeString(spec, "openapi: 3.0.3\ninfo: {title: Orders, version: '2'}\npaths: {}\n");
                case "restore" -> Files.writeString(spec, original);
                default -> throw new IllegalArgumentException("unsupported fixture source change");
            }
        }

        Map<String, Object> multiSourceEvidence() throws Exception {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("projectId", 41L); facts.put("projectCode", "orders"); facts.put("environment", "dev");
            facts.put("assetCount", database.jdbc().queryForObject("SELECT COUNT(*) FROM capability_http_api_asset", Integer.class));
            facts.put("bindingCount", database.jdbc().queryForObject("SELECT COUNT(*) FROM capability_http_api_source_binding", Integer.class));
            facts.put("inventoryCount", database.jdbc().queryForObject("SELECT COUNT(*) FROM capability_http_api_inventory_state", Integer.class));
            facts.put("detail", httpApiCatalog.detail(1L));
            facts.put("controllerMethodCalls", controllerOrder.methodCalls());
            facts.put("upstreamRequests", controllerOrderBridge.requestCount());
            facts.put("connectionCount", database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_http_api_connection", Integer.class));
            facts.put("attemptCount", database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_console_capability_invocation", Integer.class));
            facts.put("runs", database.jdbc().queryForList("SELECT id,trace_id,status,project_code FROM runtime_run"));
            facts.put("workflow", responseMap(runtimeRequest("GET", "/api/workflows/" + multiSourceWorkflowId + "/working-copy", null, Map.of())));
            return facts;
        }

        private void prepareAcceptedControllerApi() throws Exception {
            database.jdbc().update("INSERT INTO capability_scan_project "
                    + "(id,name,project_code,environment,base_url,scan_path,scan_type,status) "
                    + "VALUES (41,'Orders','orders','dev','https://not-an-execution-origin.invalid','.',"
                    + "'controller','scanned')");
            Path source = Path.of("src/test/java/com/enterprise/ai/runtime/runops/consolehttpapi/"
                    + "ControllerOrderFixtureController.java");
            var manifest = new ControllerAnnotationToolManifestScanner().scan(source,
                    new ProjectMetadata("orders", "https://not-an-execution-origin.invalid", ""));
            assertTrue(manifest.httpApiInventoryComplete());
            assertEquals(1, manifest.httpApis().size());
            HttpApiOperation operation = manifest.httpApis().get(0);
            SqlSessionTemplate session = session(database);
            HttpApiSourceBindingMapper bindings = session.getMapper(HttpApiSourceBindingMapper.class);
            HttpApiAssetService assets = new HttpApiAssetService(session.getMapper(HttpApiAssetMapper.class),
                    bindings, new HttpApiContractCanonicalizer(json));
            ControllerScanHttpApiIntakeService intake = new ControllerScanHttpApiIntakeService(assets, bindings,
                    new HttpApiInventoryStateService(session.getMapper(HttpApiInventoryStateMapper.class),
                            session.getMapper(HttpApiInventoryMemberMapper.class)));
            var project = new com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity();
            project.setId(41L); project.setProjectCode("orders"); project.setEnvironment("dev");
            project.setContextPath("");
            var plan = intake.prepare(project, List.of(scannerData(operation)), true);
            var observed = tx.execute(status -> intake.observe(plan));
            assertNotNull(observed);
            assertTrue(observed.supported() && observed.inventoryComplete());
            assertEquals(1, observed.observed());
            var owner = httpApiCatalog.detail(1L);
            assertNotNull(owner);
            assertTrue(owner.summary().sourceKinds().contains("CONTROLLER_SCAN"));
            assertEquals("orders", owner.summary().projectCode());
            httpApiCatalog.accept(1L, owner.summary().sourceSetRevision(), "fixture:orders");
            assertEquals("ACCEPTED", httpApiCatalog.detail(1L).summary().sourceStatus());
            var ordersRole = new PlatformUserRoleEntity();
            ordersRole.setUserId(platformUserId);
            Long roleId = database.jdbc().queryForObject(
                    "SELECT id FROM control_platform_role WHERE role_code = 'bmapi-fixture-role'", Long.class);
            ordersRole.setRoleId(roleId);
            ordersRole.setScopeType("PROJECT"); ordersRole.setScopeValue("orders");
            ordersRole.setCreatedAt(LocalDateTime.now());
            session.getMapper(PlatformUserRoleMapper.class).insert(ordersRole);
            controllerOrderBridge = LoopbackBridge.start(MockMvcBuilders.standaloneSetup(controllerOrder).build());
            closers.add(controllerOrderBridge);
            var connection = new com.enterprise.ai.common.capability.HttpApiConsoleContracts.ConnectionCommand(
                    1, 1L, owner.summary().qualifiedName(), 41L, "orders", "dev",
                    new com.enterprise.ai.common.capability.HttpApiConsoleContracts.ConnectionSaveRequest(
                            controllerOrderBridge.baseUrl(), "NONE", null, null));
            assertEquals("CONFIGURED", httpApiConnections.save(connection, "fixture:orders").status());
        }

        void grantTrialPermissions() {
            SqlSessionTemplate session = sessionUnchecked();
            Long roleId = database.jdbc().queryForObject(
                    "SELECT id FROM control_platform_role WHERE role_code = 'bmapi-fixture-role'", Long.class);
            for (String code : List.of(PlatformPermissions.WORKFLOW_DEBUG, PlatformPermissions.CAPABILITY_INVOKE)) {
                PlatformPermissionEntity permission = new PlatformPermissionEntity();
                permission.setPermissionCode(code); permission.setPermissionName(code);
                permission.setResourceType("PROJECT"); permission.setAction("ACCESS");
                session.getMapper(PlatformPermissionMapper.class).insert(permission);
                PlatformRolePermissionEntity grant = new PlatformRolePermissionEntity();
                grant.setRoleId(roleId); grant.setPermissionId(permission.getId());
                session.getMapper(PlatformRolePermissionMapper.class).insert(grant);
            }
        }

        void grantApiTrialAcl() {
            ControlToolAclEntity allow = new ControlToolAclEntity();
            allow.setRoleCode("bmapi-fixture-role");
            allow.setProjectId(41L); allow.setProjectCode("orders");
            allow.setTargetKind("TOOL");
            allow.setTargetName(httpApiCatalog.detail(1L).summary().qualifiedName());
            allow.setPermission("ALLOW"); allow.setEnabled(true);
            allow.setNote("isolated per-API trial grant");
            allow.setCreatedAt(LocalDateTime.now()); allow.setUpdatedAt(LocalDateTime.now());
            sessionUnchecked().getMapper(ControlToolAclMapper.class).insert(allow);
        }

        void grantMethodTrialAcl() {
            ControlToolAclEntity allow = new ControlToolAclEntity();
            allow.setRoleCode("bmapi-fixture-role"); allow.setProjectId(projectId()); allow.setProjectCode(PROJECT_CODE);
            allow.setTargetKind("TOOL"); allow.setTargetName("bmapi2d:normalizeOrderNo"); allow.setPermission("ALLOW");
            allow.setEnabled(true); allow.setNote("isolated per-method Studio trial grant");
            allow.setCreatedAt(LocalDateTime.now()); allow.setUpdatedAt(LocalDateTime.now());
            sessionUnchecked().getMapper(ControlToolAclMapper.class).insert(allow);
        }

        private SqlSessionTemplate sessionUnchecked() {
            try { return session(database); }
            catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
        }

        private HttpResponse<byte[]> trialRequest(Map<String, Object> input) throws Exception {
            byte[] body = json.writeValueAsBytes(input);
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(controlBridge.baseUrl()
                            + "/api/workflows/studio/read-only-trials"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + platformBearer)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        }

        private static CapabilityScannerClient.HttpApiData scannerData(HttpApiOperation operation) {
            return new CapabilityScannerClient.HttpApiData(operation.sourceKey(), operation.sourceLocation(),
                    operation.sourceRevision(), operation.httpMethod(), operation.contextPath(), operation.endpointPath(),
                    operation.consumes(), operation.produces(), operation.mappingConditions().stream().map(item ->
                    new CapabilityScannerClient.HttpApiMappingConditionData(item.kind(), item.name(),
                            item.operator(), item.value())).toList(), operation.parameters().stream().map(item ->
                    new CapabilityScannerClient.HttpApiParameterData(item.name(), item.location(), item.required(),
                            item.schema(), item.contentTypes())).toList(), operation.requestBody() == null ? null
                    : new CapabilityScannerClient.HttpApiRequestBodyData(operation.requestBody().location(),
                            operation.requestBody().required(), operation.requestBody().schema(),
                            operation.requestBody().contentTypes()), operation.responses().stream().map(item ->
                    new CapabilityScannerClient.HttpApiResponseData(item.status(), item.schema(),
                            item.contentTypes())).toList(), operation.authenticationState(),
                    operation.authenticationSchemes(), operation.requiredHeaderNames(), operation.sideEffect());
        }

        private String controllerApiBrowserGraph(String field) throws IOException {
            try (var stream = getClass().getResourceAsStream("/bmapi-3c-a/browser-saved-working-copy.json")) {
                assertNotNull(stream);
                var browser = json.readTree(stream);
                assertEquals("fixture-r3", browser.path("browserRevision").asText());
                return browser.path(field).asText();
            }
        }

        BrowserSurface prepareBrowserSurface() throws Exception {
            assertBrowserFixtureHasNoGlobalRole();
            registerAndSync();
            assertInitialReadOnlyDeclarationsWereAutoAccepted();
            WorkflowRelease release = createSaveReadValidateAndPublish();
            assertPublishedOwnerPins(release);
            // References are production-derived before the browser opens the detail page.
            // The created client credential stays inside the isolated fixture and is never surfaced.
            publishMcpWorkflow(release);
            browserWorkflowId = release.workflowId();
            return new BrowserSurface(controlBridge.baseUrl(), browserWorkflowId, projectId(), PROJECT_CODE,
                    List.of("bmapi2d:health", "bmapi2d:normalizeOrderNo", "bmapi2d:queryOrder"));
        }

        ApiBrowserSurface prepareApiBrowserSurface() throws Exception {
            prepareAcceptedControllerApi();
            assertBrowserFixtureHasNoGlobalRole();
            ControllerApiDraft draft = createSavedControllerApiDraft();
            Path authState = Files.createTempFile("bmapi-3c-browser-auth-", ".json");
            closers.add(() -> Files.deleteIfExists(authState));
            Map<String, Object> cookie = new LinkedHashMap<>();
            cookie.put("name", PlatformSessionCookieService.SESSION_COOKIE_NAME);
            cookie.put("value", platformBearer);
            cookie.put("domain", "127.0.0.1");
            cookie.put("path", PlatformSessionCookieService.SESSION_COOKIE_PATH);
            cookie.put("expires", java.time.Instant.now().plusSeconds(1_800).getEpochSecond());
            cookie.put("httpOnly", true);
            cookie.put("secure", false);
            cookie.put("sameSite", "Strict");
            Files.writeString(authState, json.writeValueAsString(Map.of(
                    "cookies", List.of(cookie), "origins", List.of())), StandardCharsets.UTF_8);
            return new ApiBrowserSurface(controlBridge.baseUrl(), draft.workflowId(), 41L,
                    "orders", 1L, authState.toAbsolutePath().toString());
        }

        MethodBrowserSurface prepareMethodBrowserSurface() throws Exception {
            registerAndSync(); assertInitialReadOnlyDeclarationsWereAutoAccepted(); assertBrowserFixtureHasNoGlobalRole();
            ControllerApiDraft draft = createSavedMethodTrialDraft();
            Path authState = Files.createTempFile("bmapi-2dc-browser-auth-", ".json");
            closers.add(() -> Files.deleteIfExists(authState));
            Map<String, Object> cookie = new LinkedHashMap<>();
            cookie.put("name", PlatformSessionCookieService.SESSION_COOKIE_NAME); cookie.put("value", platformBearer);
            cookie.put("domain", "127.0.0.1"); cookie.put("path", PlatformSessionCookieService.SESSION_COOKIE_PATH);
            cookie.put("expires", java.time.Instant.now().plusSeconds(1_800).getEpochSecond());
            cookie.put("httpOnly", true); cookie.put("secure", false); cookie.put("sameSite", "Strict");
            Files.writeString(authState, json.writeValueAsString(Map.of("cookies", List.of(cookie), "origins", List.of())), StandardCharsets.UTF_8);
            return new MethodBrowserSurface(controlBridge.baseUrl(), draft.workflowId(), projectId(), PROJECT_CODE,
                    "bmapi2d_normalizeOrderNo", "bmapi2d:normalizeOrderNo", authState.toAbsolutePath().toString());
        }

        private ControllerApiDraft createSavedMethodTrialDraft() throws Exception {
            Map<String, Object> create = new LinkedHashMap<>();
            create.put("projectId", projectId()); create.put("projectCode", PROJECT_CODE);
            create.put("keySlug", "bmapi-2dc-method-trial"); create.put("name", "BMAPI 2D-C Method Trial");
            create.put("description", "Isolated SDK scalar method trial"); create.put("workflowKind", "GENERAL");
            create.put("executionEngine", "GRAPH_SPEC"); create.put("graphSpecJson", methodTrialGraph());
            create.put("canvasJson", "{\"schemaVersion\":1,\"layoutVersion\":1,\"nodes\":[{\"id\":\"method\",\"position\":{\"x\":280,\"y\":170}},{\"id\":\"variable\",\"position\":{\"x\":570,\"y\":170}}],\"edges\":[{\"id\":\"method-variable\"}]}");
            create.put("inputSchemaJson", "{\"type\":\"object\",\"properties\":{\"orderNo\":{\"type\":\"string\"}},\"required\":[\"orderNo\"]}");
            create.put("outputSchemaJson", "{\"type\":\"object\"}"); create.put("extraJson", "{}");
            create.put("status", "DRAFT"); create.put("definitionAuthority", "USER"); create.put("creationChannel", "STUDIO");
            var created = runtimeRequest("POST", "/api/workflows", json.writeValueAsBytes(create), Map.of());
            assertEquals(200, created.statusCode(), runtimeBridge.lastResponseSummary());
            String workflowId = requiredText(responseMap(created), "id");
            var loaded = runtimeRequest("GET", "/api/workflows/" + workflowId + "/working-copy", null, Map.of());
            String revision = requiredText(responseMap(loaded), "revision");
            Map<String, Object> save = new LinkedHashMap<>(create);
            save.remove("projectId"); save.remove("projectCode"); save.remove("status"); save.put("baseRevision", revision);
            var saved = runtimeRequest("PUT", "/api/workflows/" + workflowId + "/working-copy", json.writeValueAsBytes(save), Map.of());
            assertEquals(200, saved.statusCode(), runtimeBridge.lastResponseSummary());
            return new ControllerApiDraft(workflowId, requiredText(responseMap(saved), "revision"));
        }

        Map<String, Object> completeMethodBrowserTrial(MethodBrowserSurface surface) throws Exception {
            assertEquals(1, capabilityHits.get()); assertEquals(1, sdkBridge.requestCount());
            assertEquals("A-1024", invocationEvidence.scalarInput.get());
            assertEquals(PROJECT_CODE, invocationEvidence.signedProject.get());
            assertEquals(PROJECT_CODE, invocationEvidence.signedTenant.get());
            assertNull(invocationEvidence.businessUser.get());
            assertTrue(invocationEvidence.businessRoles.get() == null || invocationEvidence.businessRoles.get().isEmpty());
            var runs = database.jdbc().queryForList("SELECT id, trace_id, status, snapshot_json FROM runtime_run WHERE workflow_id = ? AND entry_type = 'STUDIO_READ_ONLY_TRIAL'", surface.workflowId());
            assertEquals(1, runs.size()); var run = runs.get(0); assertEquals("COMPLETED", run.get("status"));
            String traceId = String.valueOf(run.get("trace_id"));
            var metadata = json.readTree(database.jdbc().queryForObject("SELECT metadata_json FROM runtime_trace_span WHERE trace_id = ? AND span_type = 'WORKFLOW'", String.class, traceId));
            assertEquals(String.valueOf(platformUserId), metadata.path("platformActorId").asText());
            assertEquals("STUDIO_PROJECT_TEST", metadata.path("identityMode").asText());
            assertEquals(surface.qualifiedName(), metadata.path("methodQualifiedName").asText());
            String savedGraph = database.jdbc().queryForObject("SELECT graph_spec_json FROM runtime_workflow WHERE id = ?", String.class, surface.workflowId());
            String revision = requiredText(responseMap(runtimeRequest("GET", "/api/workflows/" + surface.workflowId() + "/working-copy", null, Map.of())), "revision");
            assertEquals(revision, metadata.path("draftRevision").asText());
            assertEquals(com.enterprise.ai.common.capability.WorkflowReadOnlyTrialPolicy.graphSha256(savedGraph), metadata.path("graphSha256").asText());
            assertEquals(metadata.path("currentContractHash").asText(), metadata.path("acceptedContractHash").asText());
            assertEquals(metadata.path("currentContractHash").asText(), metadata.path("sourceContractHash").asText());
            assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_version WHERE workflow_id = ?", Integer.class, surface.workflowId()));
            assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_http_api_pin WHERE workflow_id = ?", Integer.class, surface.workflowId()));
            assertFalse(String.valueOf(run.get("snapshot_json")).contains("N-A-1024"));
            var references = publicBusinessMethodReferences(surface.methodName());
            List<Map<String, Object>> usage = json.convertValue(references.get("references"), new TypeReference<>() { });
            assertTrue(usage.stream().anyMatch(ref -> "WORKFLOW".equals(ref.get("kind")) && surface.workflowId().equals(ref.get("id")) && "DRAFT".equals(ref.get("stage"))));
            var publicTrace = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(controlBridge.baseUrl() + "/api/runops/traces/" + traceId))
                    .header("Authorization", "Bearer " + platformBearer).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, publicTrace.statusCode());
            return Map.of("runtimeRunRecordId", run.get("id"), "traceId", traceId, "revision", revision,
                    "qualifiedName", surface.qualifiedName(), "graphSha256", metadata.path("graphSha256").asText(),
                    "contractHash", metadata.path("acceptedContractHash").asText(), "sdkEndpointHits", sdkBridge.requestCount(), "sdkMethodHits", capabilityHits.get());
        }

        void revokeMethodTrialAcl() {
            assertEquals(1, database.jdbc().update("UPDATE control_tool_acl SET enabled = 0 WHERE project_code = ? AND target_name = ?", PROJECT_CODE, "bmapi2d:normalizeOrderNo"));
        }

        void assertMethodBrowserTrialDeniedWithoutAnotherDispatch(MethodBrowserSurface surface) {
            assertEquals(1, capabilityHits.get()); assertEquals(1, sdkBridge.requestCount());
            assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_run WHERE workflow_id = ? AND entry_type = 'STUDIO_READ_ONLY_TRIAL'", Integer.class, surface.workflowId()));
        }

        ApiBrowserOutcome completeApiBrowserPublish(ApiBrowserSurface surface) throws Exception {
            List<Map<String, Object>> versions = database.jdbc().queryForList(
                    "SELECT id, version, graph_spec_snapshot_json, published_by "
                            + "FROM runtime_workflow_version WHERE workflow_id = ? AND status = 'ACTIVE'",
                    surface.workflowId());
            assertEquals(1, versions.size(), "browser must publish exactly one active version");
            Map<String, Object> version = versions.get(0);
            assertEquals("platform:" + platformUserId, version.get("published_by"),
                    "the browser release must retain the Control platform-session actor");
            WorkflowRelease release = new WorkflowRelease(surface.workflowId(), number(version.get("id")),
                    String.valueOf(version.get("version")), String.valueOf(version.get("graph_spec_snapshot_json")));
            McpSurface mcp = publishMcpWorkflow(release, surface.projectCode(), surface.projectId(),
                    "bmapi-3c-api-workflow");
            McpInvocation invocation = callMcpWorkflow(mcp, Map.of("orderId", "O-321", "detailLevel", "full"));
            assertTrue(invocation.answer().contains("PAID"));
            assertTrue(invocation.answer().contains("order_state"), invocation.answer());
            assertEquals(1, controllerOrder.methodCalls());
            assertControllerApiReleaseAndTrace(release, invocation);
            assertPublicRunOps(invocation);
            return new ApiBrowserOutcome(release.versionId(), release.version(), invocation.runId(),
                    invocation.traceId(), controllerOrder.methodCalls());
        }

        Map<String, Object> completeApiBrowserTrial(ApiBrowserSurface surface) throws Exception {
            assertEquals(1, controllerOrder.methodCalls(), "one explicit browser trial must call the scanned method once");
            var runs = database.jdbc().queryForList("SELECT id, trace_id, status, snapshot_json "
                    + "FROM runtime_run WHERE workflow_id = ? AND entry_type = 'STUDIO_READ_ONLY_TRIAL'", surface.workflowId());
            assertEquals(1, runs.size());
            var run = runs.get(0); assertEquals("COMPLETED", run.get("status"));
            String traceId = String.valueOf(run.get("trace_id"));
            var metadata = json.readTree(String.valueOf(database.jdbc().queryForObject(
                    "SELECT metadata_json FROM runtime_trace_span WHERE trace_id = ? AND span_type = 'WORKFLOW'",
                    String.class, traceId)));
            assertEquals(String.valueOf(platformUserId), metadata.path("platformActorId").asText());
            assertEquals(1L, metadata.path("apiId").asLong());
            assertEquals(httpApiCatalog.detail(1L).summary().qualifiedName(), metadata.path("apiQualifiedName").asText());
            String savedGraph = database.jdbc().queryForObject("SELECT graph_spec_json FROM runtime_workflow WHERE id = ?",
                    String.class, surface.workflowId());
            assertEquals(com.enterprise.ai.common.capability.HttpApiDraftTrialPolicy.graphSha256(savedGraph),
                    metadata.path("graphSha256").asText());
            assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_http_api_pin WHERE workflow_id = ?",
                    Integer.class, surface.workflowId()));
            assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_version WHERE workflow_id = ?",
                    Integer.class, surface.workflowId()));
            assertFalse(String.valueOf(run.get("snapshot_json")).contains("synthetic-controller-value"));
            assertPublicRunOps(new McpInvocation("", String.valueOf(run.get("id")), traceId));
            return Map.of("runtimeRunRecordId", run.get("id"), "traceId", traceId,
                    "revision", metadata.path("draftRevision").asText(), "graphSha256", metadata.path("graphSha256").asText(),
                    "qualifiedName", metadata.path("apiQualifiedName").asText(), "controllerMethodCalls", controllerOrder.methodCalls());
        }

        void revokeApiTrialAcl() {
            assertEquals(1, database.jdbc().update("UPDATE control_tool_acl SET enabled = 0 WHERE project_code = 'orders' "
                    + "AND target_kind = 'TOOL' AND target_name = ?", httpApiCatalog.detail(1L).summary().qualifiedName()));
        }

        void assertBrowserTrialDeniedWithoutAnotherDispatch(ApiBrowserSurface surface) {
            assertEquals(1, controllerOrder.methodCalls());
            assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_run WHERE workflow_id = ? "
                    + "AND entry_type = 'STUDIO_READ_ONLY_TRIAL'", Integer.class, surface.workflowId()));
        }

        private void assertPublicRunOps(McpInvocation invocation) throws Exception {
            HttpResponse<byte[]> runOps = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(controlBridge.baseUrl()
                                    + "/api/runops/traces/" + invocation.traceId()))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + platformBearer)
                            .header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, runOps.statusCode(), controlBridge.lastResponseSummary());
            assertEquals("orders", map(responseMap(runOps).get("summary"), "RunOps summary").get("projectCode"));
        }

        private void assertBrowserFixtureHasNoGlobalRole() {
            Integer globalRoles = database.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM control_platform_user_role WHERE user_id = ? AND scope_type = 'GLOBAL'",
                    Integer.class, platformUserId);
            assertEquals(0, globalRoles,
                    "the browser fixture must prove Studio works with only its project-scoped role");
        }

        private RuntimeGraphSpecExecutor runtimeGraphExecutor() {
            return runtimeGraphExecutor;
        }

        private WorkflowRelease createSaveReadValidateAndPublish() throws Exception {
            assertClientGraphOmitsOwnerPins(publishedWorkflowGraph());
            Map<String, Object> create = new LinkedHashMap<>();
            create.put("projectId", projectId());
            create.put("projectCode", PROJECT_CODE);
            create.put("keySlug", "bmapi-2d-workflow");
            create.put("name", "BMAPI 2D Workflow");
            create.put("description", "Isolated Business Method Workflow");
            create.put("workflowKind", "GENERAL");
            create.put("executionEngine", "GRAPH_SPEC");
            create.put("graphSpecJson", publishedWorkflowGraph());
            create.put("canvasJson", workflowCanvas());
            create.put("inputSchemaJson", "{\"type\":\"object\",\"properties\":{\"orderNo\":{\"type\":\"string\"},\"request\":{\"type\":\"object\"}},\"required\":[\"orderNo\",\"request\"]}");
            create.put("outputSchemaJson", "{\"type\":\"object\"}");
            create.put("extraJson", "{\"fixture\":\"bmapi-2d\"}");
            create.put("status", "DRAFT");
            create.put("definitionAuthority", "USER");
            create.put("creationChannel", "STUDIO");

            HttpResponse<byte[]> created = runtimeRequest("POST", "/api/workflows", json.writeValueAsBytes(create), Map.of());
            assertEquals(200, created.statusCode());
            String workflowId = requiredText(responseMap(created), "id");

            HttpResponse<byte[]> loaded = runtimeRequest("GET", "/api/workflows/" + workflowId + "/working-copy", null, Map.of());
            assertEquals(200, loaded.statusCode());
            Map<String, Object> workingCopy = responseMap(loaded);
            assertEquals(publishedWorkflowGraphNodeCount(), nodeCount(workingCopy.get("graphSpecJson")));
            assertWorkingCopyOmitsClientCatalogFields(workingCopy.get("graphSpecJson"));
            String revision = requiredText(workingCopy, "revision");

            Map<String, Object> save = new LinkedHashMap<>();
            save.put("graphSpecJson", publishedWorkflowGraph());
            save.put("canvasJson", workflowCanvas());
            save.put("extraJson", "{\"fixture\":\"bmapi-2d\",\"saved\":true}");
            save.put("baseRevision", revision);
            save.put("keySlug", "bmapi-2d-workflow");
            save.put("name", "BMAPI 2D Workflow");
            save.put("description", "Isolated Business Method Workflow");
            save.put("inputSchemaJson", "{\"type\":\"object\",\"properties\":{\"orderNo\":{\"type\":\"string\"},\"request\":{\"type\":\"object\"}},\"required\":[\"orderNo\",\"request\"]}");
            save.put("outputSchemaJson", "{\"type\":\"object\"}");
            save.put("workflowKind", "GENERAL");
            save.put("executionEngine", "GRAPH_SPEC");
            save.put("definitionAuthority", "USER");
            save.put("creationChannel", "STUDIO");
            HttpResponse<byte[]> saved = runtimeRequest("PUT", "/api/workflows/" + workflowId + "/working-copy",
                    json.writeValueAsBytes(save), Map.of());
            assertEquals(200, saved.statusCode());
            Map<String, Object> savedWorkingCopy = responseMap(saved);
            String savedRevision = requiredText(savedWorkingCopy, "revision");
            assertFalse(savedRevision.equals(revision), "Studio save must advance the persisted working-copy revision");

            HttpResponse<byte[]> readBack = runtimeRequest("GET", "/api/workflows/" + workflowId + "/working-copy", null, Map.of());
            assertEquals(200, readBack.statusCode());
            Map<String, Object> readBackBody = responseMap(readBack);
            assertEquals(savedRevision, readBackBody.get("revision"));
            assertEquals(publishedWorkflowGraphNodeCount(), nodeCount(readBackBody.get("graphSpecJson")));
            assertWorkingCopyOmitsClientCatalogFields(readBackBody.get("graphSpecJson"));

            HttpResponse<byte[]> validation = runtimeRequest("POST", "/api/workflows/" + workflowId + "/versions/validate",
                    new byte[0], Map.of());
            assertEquals(200, validation.statusCode());
            assertTrue(Boolean.TRUE.equals(responseMap(validation).get("valid")),
                    "the Runtime release validator must accept the saved TOOL-only GraphSpec");

            Map<String, Object> publish = Map.of(
                    "version", "1.0.0", "rolloutPercent", 100, "note", "isolated acceptance", "baseRevision", savedRevision);
            byte[] publishBody = json.writeValueAsBytes(publish);
            String publishPath = "/api/workflows/" + workflowId + "/versions/publish";
            Map<String, String> signatures = new InternalServiceAuthSigner(INTERNAL_SECRET).sign(
                    "POST", publishPath, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                    String.valueOf(platformUserId), publishBody);
            HttpResponse<byte[]> published = runtimeRequest("POST", publishPath, publishBody, signatures);
            assertEquals(200, published.statusCode(), runtimeBridge.lastResponseSummary());
            Map<String, Object> version = responseMap(published);
            Long versionId = number(version.get("id"));
            assertNotNull(versionId);
            assertEquals("ACTIVE", version.get("status"));
            return new WorkflowRelease(workflowId, versionId, requiredText(version, "version"),
                    requiredText(version, "graphSpecSnapshotJson"));
        }

        private WorkflowRelease createSaveReadValidateAndPublishControllerApi() throws Exception {
            ControllerApiDraft draft = createSavedControllerApiDraft();
            String workflowId = draft.workflowId();
            String savedRevision = draft.revision();
            HttpResponse<byte[]> validation = runtimeRequest("POST",
                    "/api/workflows/" + workflowId + "/versions/validate", new byte[0], Map.of());
            assertEquals(200, validation.statusCode(), runtimeBridge.lastResponseSummary());
            assertTrue(Boolean.TRUE.equals(responseMap(validation).get("valid")),
                    "saved browser API graph must pass Runtime release validation: " + runtimeBridge.lastResponseSummary());
            byte[] publishBody = json.writeValueAsBytes(Map.of("version", "1.0.0", "rolloutPercent", 100,
                    "note", "isolated API acceptance", "baseRevision", savedRevision));
            String publishPath = "/api/workflows/" + workflowId + "/versions/publish";
            Map<String, String> signatures = new InternalServiceAuthSigner(INTERNAL_SECRET).sign(
                    "POST", publishPath, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                    String.valueOf(platformUserId), publishBody);
            HttpResponse<byte[]> published = runtimeRequest("POST", publishPath, publishBody, signatures);
            assertEquals(200, published.statusCode(), runtimeBridge.lastResponseSummary());
            Map<String, Object> version = responseMap(published);
            assertEquals("ACTIVE", version.get("status"));
            return new WorkflowRelease(workflowId, number(version.get("id")),
                    requiredText(version, "version"), requiredText(version, "graphSpecSnapshotJson"));
        }

        private ControllerApiDraft createSavedControllerApiDraft() throws Exception {
            return createSavedControllerApiDraft(controllerApiBrowserGraph("graphSpecJson"));
        }

        private ControllerApiDraft createSavedControllerApiDraft(String graph) throws Exception {
            String canvas = controllerApiBrowserGraph("canvasJson");
            assertClientGraphOmitsOwnerPins(graph);
            String inputSchema = """
                    {"type":"object","properties":{"orderId":{"type":"string"},
                    "detailLevel":{"type":"string"}},"required":["orderId"]}
                    """;
            Map<String, Object> create = new LinkedHashMap<>();
            create.put("projectId", 41L);
            create.put("projectCode", "orders");
            create.put("keySlug", "bmapi-3c-api-workflow");
            create.put("name", "BMAPI 3C API Workflow");
            create.put("description", "Isolated Controller API Workflow");
            create.put("workflowKind", "GENERAL");
            create.put("executionEngine", "GRAPH_SPEC");
            create.put("graphSpecJson", graph);
            create.put("canvasJson", canvas);
            create.put("inputSchemaJson", inputSchema);
            create.put("outputSchemaJson", "{\"type\":\"object\"}");
            create.put("extraJson", "{\"fixture\":\"bmapi-3c-b\"}");
            create.put("status", "DRAFT");
            create.put("definitionAuthority", "USER");
            create.put("creationChannel", "STUDIO");
            HttpResponse<byte[]> created = runtimeRequest("POST", "/api/workflows", json.writeValueAsBytes(create),
                    Map.of());
            assertEquals(200, created.statusCode(), runtimeBridge.lastResponseSummary());
            String workflowId = requiredText(responseMap(created), "id");
            HttpResponse<byte[]> loaded = runtimeRequest("GET", "/api/workflows/" + workflowId + "/working-copy",
                    null, Map.of());
            assertEquals(200, loaded.statusCode(), runtimeBridge.lastResponseSummary());
            String revision = requiredText(responseMap(loaded), "revision");
            Map<String, Object> save = new LinkedHashMap<>(create);
            save.remove("projectId"); save.remove("projectCode"); save.remove("status");
            save.put("baseRevision", revision);
            HttpResponse<byte[]> saved = runtimeRequest("PUT", "/api/workflows/" + workflowId + "/working-copy",
                    json.writeValueAsBytes(save), Map.of());
            assertEquals(200, saved.statusCode(), runtimeBridge.lastResponseSummary());
            String savedRevision = requiredText(responseMap(saved), "revision");
            HttpResponse<byte[]> readBack = runtimeRequest("GET", "/api/workflows/" + workflowId + "/working-copy",
                    null, Map.of());
            assertEquals(200, readBack.statusCode());
            var savedGraph = json.readTree(requiredText(responseMap(readBack), "graphSpecJson"));
            assertEquals("api-node", savedGraph.path("entryNodeId").asText());
            assertEquals("variable_1790214522008", savedGraph.path("exitNodeIds").get(0).asText());
            assertEquals("params.orderId", savedGraph.at("/nodes/0/config/inputMapping/pathParams.orderId").asText());
            assertEquals("nodeOutput.api-node.state",
                    savedGraph.at("/nodes/1/config/assignments/order_state").asText());
            return new ControllerApiDraft(workflowId, savedRevision);
        }

        private void assertPublishedOwnerPins(WorkflowRelease release) throws Exception {
            var graph = json.readTree(release.graphSpecSnapshotJson());
            for (String qualifiedName : List.of("bmapi2d:health", "bmapi2d:normalizeOrderNo", "bmapi2d:queryOrder")) {
                var ref = graph.path("nodes").findValues("ref").stream()
                        .filter(candidate -> qualifiedName.equals(candidate.path("qualifiedName").asText()))
                        .findFirst().orElseThrow(() -> new AssertionError("missing published pin for " + qualifiedName));
                Map<String, Object> source = sourceToolDefinition(qualifiedName);
                assertEquals(number(source.get("assetId")), ref.path("assetId").asLong(),
                        "publish must pin the stable source asset rather than a Tool projection");
                assertEquals(number(source.get("acceptedRevisionId")), ref.path("acceptedRevisionId").asLong());
                assertEquals("BUSINESS_METHOD", ref.path("assetType").asText());
                assertEquals(source.get("contractHash"), ref.path("contractHash").asText());
                assertTrue(ref.path("contractHash").asText().matches("[0-9a-f]{64}"));
            }
        }

        private void assertControllerApiReleaseAndTrace(WorkflowRelease release,
                                                        McpInvocation invocation) throws Exception {
            var owner = httpApiCatalog.detail(1L);
            var published = json.readTree(release.graphSpecSnapshotJson());
            var ref = published.at("/nodes/0/ref");
            assertEquals(owner.summary().qualifiedName(), ref.path("qualifiedName").asText());
            assertEquals(owner.summary().acceptedContractHash(), ref.path("contractHash").asText());
            assertTrue(ref.path("definitionId").asLong() > 0);
            assertFalse(release.graphSpecSnapshotJson().contains(controllerOrderBridge.baseUrl()));
            List<Map<String, Object>> rows = database.jdbc().queryForList(
                    "SELECT api_id, qualified_name, accepted_contract_hash, source_set_revision, "
                            + "workflow_version_id, connection_revision FROM runtime_workflow_http_api_pin "
                            + "WHERE id = ?", ref.path("definitionId").asLong());
            assertEquals(1, rows.size());
            assertEquals(1L, number(rows.get(0).get("api_id")));
            assertEquals(release.versionId(), number(rows.get(0).get("workflow_version_id")));
            assertEquals(owner.summary().qualifiedName(), rows.get(0).get("qualified_name"));
            assertEquals(owner.summary().acceptedContractHash(), rows.get(0).get("accepted_contract_hash"));
            assertEquals(owner.summary().sourceSetRevision(), rows.get(0).get("source_set_revision"));
            assertEquals(1L, number(rows.get(0).get("connection_revision")));
            List<Map<String, Object>> runs = database.jdbc().queryForList(
                    "SELECT run_type, entry_type, status, workflow_id, workflow_version_id, "
                            + "input_summary, output_summary FROM runtime_run WHERE trace_id = ?",
                    invocation.traceId());
            assertEquals(1, runs.size(), "API Workflow must not create a Console Run");
            assertEquals("MCP", runs.get(0).get("run_type"));
            assertEquals("MCP", runs.get(0).get("entry_type"));
            assertEquals("COMPLETED", runs.get(0).get("status"));
            assertEquals(release.workflowId(), runs.get(0).get("workflow_id"));
            assertEquals(release.versionId(), number(runs.get(0).get("workflow_version_id")));
            List<Map<String, Object>> spans = database.jdbc().queryForList(
                    "SELECT span_type, node_id, tool_name, status, metadata_json, input_summary, output_summary "
                            + "FROM runtime_trace_span WHERE trace_id = ? ORDER BY id", invocation.traceId());
            assertTrue(spans.stream().anyMatch(span -> "api-node".equals(span.get("node_id"))
                    && "SUCCESS".equals(span.get("status"))));
            assertTrue(spans.stream().anyMatch(span -> "variable_1790214522008".equals(span.get("node_id"))
                    && "SUCCESS".equals(span.get("status"))));
            assertFalse(json.writeValueAsString(runs).contains("synthetic-controller-value"));
            assertFalse(json.writeValueAsString(spans).contains("synthetic-controller-value"));
            Map<String, Object> impact = publicApiReferences();
            assertEquals("COMPLETE", impact.get("runtimeEvidence"));
            List<Map<String, Object>> usage = json.convertValue(impact.get("references"), new TypeReference<>() { });
            assertTrue(usage.stream().anyMatch(item -> "WORKFLOW".equals(item.get("kind"))
                    && release.workflowId().equals(item.get("id")) && "DRAFT".equals(item.get("stage"))));
            assertTrue(usage.stream().anyMatch(item -> "WORKFLOW".equals(item.get("kind"))
                    && release.workflowId().equals(item.get("id")) && "PUBLISHED".equals(item.get("stage"))
                    && release.versionId() == number(item.get("versionId"))));
        }

        private void assertWorkingCopyOmitsClientCatalogFields(Object graphJson) throws Exception {
            var graph = json.readTree(String.valueOf(graphJson));
            for (var ref : graph.path("nodes").findValues("ref")) {
                assertTrue(ref.hasNonNull("qualifiedName"));
                assertFalse(ref.has("assetType"), "Studio working copy must not persist a catalog projection type");
                assertFalse(ref.has("projectionId"), "Studio working copy must not persist a catalog projection id");
                assertFalse(ref.has("sourceQualifiedName"), "Studio working copy must not persist a duplicated source name");
            }
        }

        private static void assertClientGraphOmitsOwnerPins(String graphJson) {
            assertFalse(graphJson.contains("\"definitionId\""),
                    "the Studio request must not submit an owner definition pin");
            assertFalse(graphJson.contains("\"contractHash\""),
                    "the Studio request must not submit a frontend-obtained contract hash");
        }

        private McpInvocation callPublishedMcpWorkflow(WorkflowRelease release) throws Exception {
            return callMcpWorkflow(publishMcpWorkflow(release));
        }

        private McpSurface publishMcpWorkflow(WorkflowRelease release) throws Exception {
            return publishMcpWorkflow(release, PROJECT_CODE, projectId(), "bmapi-2d-workflow");
        }

        private McpSurface publishMcpWorkflow(WorkflowRelease release, String projectCode,
                                              Long projectId, String toolName) throws Exception {
            SqlSessionTemplate session = session(database);
            MybatisMcpPublicationRepository publicationsRepository = new MybatisMcpPublicationRepository(
                    session.getMapper(McpPublicationMapper.class), session.getMapper(McpPublicationItemMapper.class),
                    session.getMapper(McpPublicationRevisionMapper.class), json);
            MybatisMcpClientRepository clientsRepository = new MybatisMcpClientRepository(
                    session.getMapper(McpClientMapper.class), json);
            MybatisMcpCallAuditRepository callAuditRepository = new MybatisMcpCallAuditRepository(
                    session.getMapper(McpCallLogMapper.class));
            ControlToolAclDecisionService acl = new ControlToolAclDecisionService(
                    session.getMapper(ControlToolAclMapper.class));
            WorkflowItemContractResolver workflowResolver = new WorkflowItemContractResolver(runtimeProxy(), json);
            CompositeMcpItemContractResolver contracts = new CompositeMcpItemContractResolver(List.of(workflowResolver));
            McpPrecheckService precheck = new McpPrecheckService(publicationsRepository, contracts);
            McpPublicationApplicationService publications = transactional(new McpPublicationApplicationService(
                    publicationsRepository, clientsRepository, precheck, contracts, json));
            McpClientApplicationService clients = transactional(new McpClientApplicationService(
                    clientsRepository, publications, acl));

            var publication = publications.create(toolName + "-mcp", "Isolated published Workflow surface");
            publications.addItem(publication.id(), McpPublicationItemKind.WORKFLOW, release.workflowId(),
                    null, null, "READ");
            var published = publications.publish(publication.id(), false);
            assertEquals("PUBLISHED", published.publication().state());
            assertEquals(List.of(toolName), published.availableToolNames());

            ControlToolAclEntity allow = new ControlToolAclEntity();
            allow.setRoleCode("bmapi-mcp-role");
            allow.setProjectId(projectId);
            allow.setProjectCode(projectCode);
            allow.setTargetKind("TOOL");
            allow.setTargetName(release.workflowId());
            allow.setPermission("ALLOW");
            allow.setNote("isolated MCP Workflow acceptance");
            allow.setEnabled(true);
            allow.setCreatedAt(LocalDateTime.now());
            allow.setUpdatedAt(LocalDateTime.now());
            session.getMapper(ControlToolAclMapper.class).insert(allow);

            McpClientApplicationService.CreatedClient client = clients.create(
                    publication.id(), toolName + "-client", projectId, projectCode, "test", projectCode,
                    List.of("bmapi-mcp-role"), List.of(), null);
            assertNotNull(client.client().id());

            McpHubProperties properties = new McpHubProperties();
            TrustedMcpRuntimeExecutionGateway gateway = new TrustedMcpRuntimeExecutionGateway(
                    new InternalServiceAuthSigner(INTERNAL_SECRET), json, properties, runtimeBridge.baseUrl());
            McpProtocolApplicationService protocol = new McpProtocolApplicationService(
                    clientsRepository, publicationsRepository, callAuditRepository, acl, gateway, json);
            McpEndpointController endpoint = new McpEndpointController(new McpProtocolRequestGuard(properties), protocol,
                    properties, json, new McpAuditPayloadSanitizer(json, properties));
            LoopbackBridge mcpBridge = LoopbackBridge.start(MockMvcBuilders.standaloneSetup(endpoint)
                    .setControllerAdvice(new McpProtocolExceptionHandler(protocol)).build());
            closers.add(mcpBridge);

            return new McpSurface(mcpBridge, client.plaintextApiKey(), toolName);
        }

        private McpInvocation callMcpWorkflow(McpSurface surface) throws Exception {
            return callMcpWorkflow(surface, Map.of("orderNo", "A-1024",
                    "request", Map.of("orderNo", "A-1024", "customerId", "customer-7")));
        }

        private McpInvocation callMcpWorkflow(McpSurface surface, Map<String, Object> arguments) throws Exception {
            Map<String, Object> listed = mcpRequest(surface.bridge, surface.apiKey, Map.of(
                    "jsonrpc", "2.0", "id", "tools-list", "method", "tools/list", "params", Map.of()));
            Map<String, Object> listResult = map(listed.get("result"), "MCP tools/list result");
            List<Map<String, Object>> tools = json.convertValue(listResult.get("tools"), new TypeReference<>() { });
            assertEquals(1, tools.size());
            assertEquals(surface.toolName, tools.get(0).get("name"));

            Map<String, Object> called = mcpRequest(surface.bridge, surface.apiKey, Map.of(
                    "jsonrpc", "2.0", "id", "tools-call", "method", "tools/call",
                    "params", Map.of("name", surface.toolName, "arguments", arguments)));
            Map<String, Object> callResult = map(called.get("result"), "MCP tools/call result");
            assertEquals(Boolean.FALSE, callResult.get("isError"),
                    "MCP structuredContent=" + callResult.get("structuredContent")
                            + "; runtime=" + runtimeBridge.lastResponseSummary());
            Map<String, Object> structured = map(callResult.get("structuredContent"), "MCP structuredContent");
            Map<String, Object> meta = map(callResult.get("_meta"), "MCP execution metadata");
            return new McpInvocation(
                    requiredText(structured, "answer"), requiredText(meta, "reachai/runId"),
                    requiredText(meta, "reachai/traceId"));
        }

        private String callMcpWorkflowError(McpSurface surface, Map<String, Object> arguments) throws Exception {
            Map<String, Object> called = mcpRequest(surface.bridge, surface.apiKey, Map.of(
                    "jsonrpc", "2.0", "id", "tools-rejected", "method", "tools/call",
                    "params", Map.of("name", surface.toolName, "arguments", arguments)));
            Map<String, Object> result = map(called.get("result"), "MCP rejected tools/call result");
            assertEquals(Boolean.TRUE, result.get("isError"), "unexpected MCP result=" + result.get("structuredContent"));
            return requiredText(map(result.get("structuredContent"), "MCP rejected structuredContent"),
                    "errorCode");
        }

        private void assertMcpPolicyDenied(McpSurface surface, Map<String, Object> arguments) throws Exception {
            Map<String, Object> called = mcpRequest(surface.bridge, surface.apiKey, Map.of(
                    "jsonrpc", "2.0", "id", "tools-policy-denied", "method", "tools/call",
                    "params", Map.of("name", surface.toolName, "arguments", arguments)));
            assertEquals(-32002, map(called.get("error"), "MCP policy error").get("code"));
            assertNull(called.get("result"));
            assertEquals("POLICY", database.jdbc().queryForObject(
                    "SELECT error_category FROM control_mcp_call_log WHERE method = 'tools/call' "
                            + "ORDER BY id DESC LIMIT 1", String.class));
        }

        private void assertMcpRejected(McpSurface surface, String expectedCode, int expectedHits) throws Exception {
            Map<String, Object> called = mcpRequest(surface.bridge, surface.apiKey, Map.of(
                    "jsonrpc", "2.0", "id", "tools-rejected", "method", "tools/call",
                    "params", Map.of("name", surface.toolName, "arguments", Map.of(
                            "orderNo", "A-1024",
                            "request", Map.of("orderNo", "A-1024", "customerId", "customer-7")))));
            Map<String, Object> result = map(called.get("result"), "MCP rejected tools/call result");
            assertEquals(Boolean.TRUE, result.get("isError"));
            Map<String, Object> structured = map(result.get("structuredContent"), "MCP rejected structuredContent");
            assertEquals(expectedCode, structured.get("errorCode"));
            assertEquals(expectedHits, capabilityHits.get(), expectedCode + " must reject before the SDK endpoint");
        }

        private DebugExecution runStudioDebug(WorkflowRelease release) throws Exception {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("workflowId", release.workflowId());
            request.put("message", "isolated Studio debug");
            request.put("inputParams", Map.of(
                    "orderNo", "A-1024",
                    "request", Map.of("orderNo", "A-1024", "customerId", "customer-7")));
            request.put("debugOptions", Map.of());
            HttpResponse<byte[]> response = runtimeRequest("POST", "/api/workflows/studio/debug-run",
                    json.writeValueAsBytes(request), Map.of());
            assertEquals(200, response.statusCode());
            Map<String, Object> result = responseMap(response);
            return new DebugExecution(Boolean.TRUE.equals(result.get("success")),
                    String.valueOf(result.getOrDefault("errorCode", "")), requiredText(result, "traceId"));
        }

        private <T> void syncSource(Class<T> fixtureType, java.util.function.Supplier<T> fixtureSupplier) {
            AnnotationConfigApplicationContext sourceContext = new AnnotationConfigApplicationContext();
            sourceContext.registerBean(fixtureType, fixtureSupplier);
            sourceContext.refresh();
            closers.add(sourceContext);
            new ReachAiRegistryClient(sdkProperties,
                    new ReachCapabilityBeanScanner(sourceContext, sdkProperties)).scanAndSyncCapabilities();
        }

        private RuntimeProxyClient runtimeProxy() {
            return (RuntimeProxyClient) Proxy.newProxyInstance(RuntimeProxyClient.class.getClassLoader(),
                    new Class<?>[]{RuntimeProxyClient.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "createWorkflow" -> runtimeProxyResponse("POST", "/api/workflows",
                                json.writeValueAsBytes(arguments[0]), Map.of());
                        case "createAgent" -> runtimeProxyResponse("POST", "/api/agents",
                                json.writeValueAsBytes(arguments[0]), Map.of());
                        case "getAgent" -> runtimeProxyResponse("/api/agents/" + arguments[0]);
                        case "listAgentConfigVersions" -> runtimeProxyResponse("/api/agents/" + arguments[0] + "/config-versions");
                        case "saveAgentConfigDraft" -> runtimeProxyResponse("PUT", "/api/agents/" + arguments[0] + "/config-versions/draft",
                                json.writeValueAsBytes(arguments[1]), Map.of());
                        case "publishAgentConfigVersion" -> runtimeProxyResponse("POST", "/api/agents/" + arguments[0]
                                + "/config-versions/" + arguments[1] + "/publish", json.writeValueAsBytes(arguments[2]), Map.of());
                        case "getWorkflow" -> runtimeProxyResponse("/api/workflows/" + arguments[0]);
                        case "listWorkflows" -> runtimeProxyResponse("GET", queryPath("/api/workflows", queryParameters(
                                "projectId", arguments[0], "projectCode", arguments[1], "workflowKind", arguments[2],
                                "definitionAuthority", arguments[3], "status", arguments[4])), null, Map.of());
                        case "graphNodeTypes" -> runtimeProxyResponse("/api/workflows/graph-node-types");
                        case "workflowWorkingCopy" -> runtimeProxyResponse(
                                "/api/workflows/" + arguments[0] + "/working-copy");
                        case "saveWorkflowWorkingCopy" -> runtimeProxyResponse("PUT",
                                "/api/workflows/" + arguments[0] + "/working-copy",
                                json.writeValueAsBytes(arguments[1]), Map.of());
                        case "validateWorkflowRuntime" -> runtimeProxyResponse("POST", "/api/workflows/runtime-validation",
                                json.writeValueAsBytes(arguments[0]), Map.of());
                        case "listWorkflowVersions" -> runtimeProxyResponse("/api/workflows/" + arguments[0] + "/versions");
                        case "validateWorkflowVersion" -> runtimeProxyResponse("POST",
                                "/api/workflows/" + arguments[0] + "/versions/validate", new byte[0], Map.of());
                        case "publishWorkflowVersion" -> runtimeProxyResponse("POST",
                                "/api/workflows/" + arguments[0] + "/versions/publish",
                                (byte[]) arguments[2], castHeaders(arguments[1]));
                        case "listWorkflowCredentials" -> runtimeProxyResponse("GET", queryPath(
                                "/api/workflows/credentials", queryParameters(
                                        "projectId", arguments[0], "projectCode", arguments[1])), null, Map.of());
                        case "createWorkflowCredential" -> runtimeProxyResponse("POST", "/api/workflows/credentials",
                                json.writeValueAsBytes(arguments[0]), Map.of());
                        case "updateWorkflowCredential" -> runtimeProxyResponse("PUT", "/api/workflows/credentials/" + arguments[0],
                                json.writeValueAsBytes(arguments[1]), Map.of());
                        case "debugWorkflowNode" -> runtimeProxyResponse("POST", "/api/workflows/studio/debug-node",
                                json.writeValueAsBytes(arguments[0]), Map.of());
                        case "debugWorkflowRun" -> runtimeProxyResponse("POST", "/api/workflows/studio/debug-run",
                                json.writeValueAsBytes(arguments[0]), Map.of());
                        case "capabilityReferences" -> runtimeProxyCapabilityReferences(
                                castHeaders(arguments[0]), (byte[]) arguments[1]);
                        case "readHttpApiConnection" -> runtimeProxyResponse("POST",
                                "/internal/runtime/http-api-connections", (byte[]) arguments[1],
                                castHeaders(arguments[0]));
                        case "saveHttpApiConnection" -> runtimeProxyResponse("PUT",
                                "/internal/runtime/http-api-connections", (byte[]) arguments[1], castHeaders(arguments[0]));
                        case "readHttpApiCatalogStates" -> runtimeProxyResponse("POST",
                                "/internal/runtime/http-api-catalog-states", (byte[]) arguments[1], castHeaders(arguments[0]));
                        case "invokeHttpApi" -> runtimeProxyResponse("POST",
                                "/internal/runtime/http-api-invocations", (byte[]) arguments[1], castHeaders(arguments[0]));
                        case "getHttpApiInvocation" -> runtimeProxyResponse("GET",
                                "/internal/runtime/http-api-invocations/" + arguments[0], null, castHeaders(arguments[1]));
                        case "invokeConsoleCapability" -> runtimeProxyResponse("POST",
                                "/internal/runtime/console-capability-invocations", (byte[]) arguments[1], castHeaders(arguments[0]));
                        case "getConsoleCapabilityInvocation" -> runtimeProxyResponse("GET",
                                "/internal/runtime/console-capability-invocations/" + arguments[0], null, castHeaders(arguments[1]));
                        case "runReadOnlyApiDraftTrial" -> runtimeProxyResponse("POST",
                                "/internal/runtime/workflows/studio/read-only-trials", (byte[]) arguments[1],
                                castHeaders(arguments[0]));
                        case "runOpsDetail" -> runtimeProxyResponse("/api/runops/traces/" + arguments[0]);
                        case "runOpsRecent", "runOpsDiagnostics" -> runtimeProxyResponse("GET", queryPath(
                                "runOpsRecent".equals(method.getName()) ? "/api/runops/traces/recent" : "/api/runops/diagnostics",
                                queryParameters("projectCode", arguments[0], "status", arguments[1],
                                        "runType", arguments[2], "entryType", arguments[3], "agentId", arguments[4],
                                        "userId", arguments[5], "keyword", arguments[6],
                                        "limit", arguments[7], "days", arguments[8])), null, Map.of());
                        case "toString" -> "bmapi-2d-loopback-runtime-proxy";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> throw new UnsupportedOperationException("unexpected RuntimeProxyClient call "
                                + method.getName());
                    });
        }

        private ResponseEntity<Object> runtimeProxyResponse(String path) throws Exception {
            return runtimeProxyResponse("GET", path, null, Map.of());
        }

        private ResponseEntity<Object> runtimeProxyResponse(
                String method, String path, byte[] requestBody, Map<String, String> headers) throws Exception {
            HttpResponse<byte[]> response = runtimeRequest(method, path, requestBody, headers);
            Object responseBody = response.body().length == 0 ? null : json.readValue(response.body(), Object.class);
            return ResponseEntity.status(response.statusCode()).body(responseBody);
        }

        private static Map<String, Object> queryParameters(Object... pairs) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            for (int index = 0; index + 1 < pairs.length; index += 2) {
                parameters.put(String.valueOf(pairs[index]), pairs[index + 1]);
            }
            return parameters;
        }

        private static String queryPath(String path, Map<String, ?> parameters) {
            StringBuilder query = new StringBuilder();
            parameters.forEach((name, value) -> {
                if (value == null || String.valueOf(value).isBlank()) return;
                if (!query.isEmpty()) query.append('&');
                query.append(java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8));
                query.append('=');
                query.append(java.net.URLEncoder.encode(String.valueOf(value), java.nio.charset.StandardCharsets.UTF_8));
            });
            return query.isEmpty() ? path : path + "?" + query;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, String> castHeaders(Object raw) {
            return (Map<String, String>) raw;
        }

        private Map<String, Object> runtimeProxyCapabilityReferences(Map<String, String> headers,
                                                                       byte[] exactBody) throws Exception {
            if (changeReferenceUnavailable) throw new IllegalStateException("isolated reference transport unavailable");
            HttpResponse<byte[]> response = runtimeRequest("POST", "/internal/runtime/capability-references",
                    exactBody, headers);
            assertEquals(200, response.statusCode(), "Runtime reference bridge=" + runtimeBridge.lastResponseSummary());
            return responseMap(response);
        }

        private Map<String, Object> mcpRequest(LoopbackBridge mcpBridge, String apiKey,
                                               Map<String, Object> body) throws Exception {
            return mcpRequest(mcpBridge, apiKey, body, false);
        }

        private Map<String, Object> mcpRequest(LoopbackBridge mcpBridge, String apiKey,
                                               Map<String, Object> body, boolean allowAuthenticationDenial) throws Exception {
            byte[] payload = json.writeValueAsBytes(body);
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(mcpBridge.baseUrl() + "/mcp"))
                            .timeout(Duration.ofSeconds(allowAuthenticationDenial ? 60 : 15))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/event-stream")
                            .header("MCP-Protocol-Version", "2025-11-25")
                            .header("Authorization", "Bearer " + apiKey)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (!allowAuthenticationDenial || response.statusCode() != 401) {
                assertEquals(200, response.statusCode(), "MCP bridge=" + mcpBridge.lastResponseSummary());
            }
            return json.readValue(response.body(), new TypeReference<>() { });
        }

        private void assertMcpTrace(McpInvocation invocation) throws Exception {
            List<Map<String, Object>> spans = database.jdbc().queryForList(
                    "SELECT span_type, node_id, tool_name, status, metadata_json FROM runtime_trace_span WHERE trace_id = ? ORDER BY id",
                    invocation.traceId());
            assertTrue(spans.stream().anyMatch(span -> "MCP_TOOLS_CALL".equals(span.get("span_type"))
                    && "SUCCESS".equals(span.get("status"))), "missing successful MCP root trace span: " + spans);
            List<Map<String, Object>> nodes = spans.stream()
                    .filter(span -> "WORKFLOW_NODE".equals(span.get("span_type"))).toList();
            assertEquals(3, nodes.size(), "every Workflow TOOL must persist a child trace span");
            List<String> qualifiedNames = nodes.stream().map(span -> {
                try {
                    return requiredText(json.readValue(String.valueOf(span.get("metadata_json")), new TypeReference<>() { }),
                            "qualifiedName");
                } catch (Exception failure) {
                    throw new AssertionError("invalid Workflow TOOL trace metadata", failure);
                }
            }).sorted().toList();
            assertEquals(List.of("bmapi2d:health", "bmapi2d:normalizeOrderNo", "bmapi2d:queryOrder"), qualifiedNames);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> map(Object value, String description) {
            if (value instanceof Map<?, ?> raw) {
                Map<String, Object> copied = new LinkedHashMap<>();
                raw.forEach((key, item) -> copied.put(String.valueOf(key), item));
                return copied;
            }
            throw new AssertionError(description + " must be an object: " + value);
        }

        private Map<String, Object> sourceToolDefinition(String qualifiedName) throws Exception {
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(capabilityBridge.baseUrl() + "/internal/capability/tools/" + qualifiedName))
                            .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            return json.readValue(response.body(), new TypeReference<>() { });
        }

        private HttpResponse<byte[]> runtimeRequest(String method, String path, byte[] body,
                                                     Map<String, String> headers) throws IOException, InterruptedException {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(runtimeBridge.baseUrl() + path))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json");
                request.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            }
            if (headers != null) headers.forEach(request::header);
            return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        private Map<String, Object> responseMap(HttpResponse<byte[]> response) throws IOException {
            return json.readValue(response.body(), new TypeReference<>() { });
        }

        private static String requiredText(Map<String, Object> body, String field) {
            Object value = body == null ? null : body.get(field);
            if (value == null || String.valueOf(value).isBlank()) {
                throw new AssertionError("missing response field " + field);
            }
            return String.valueOf(value);
        }

        private static Long number(Object value) {
            return value instanceof Number number ? number.longValue() : null;
        }

        private int nodeCount(Object graphJson) throws Exception {
            return json.readTree(String.valueOf(graphJson)).path("nodes").size();
        }

        private static int publishedWorkflowGraphNodeCount() {
            return 3;
        }

        private record WorkflowRelease(String workflowId, long versionId, String version,
                                       String graphSpecSnapshotJson) {
        }

        private record ControllerApiDraft(String workflowId, String revision) {
        }

        private record McpInvocation(String answer, String runId, String traceId) {
        }

        private record DebugExecution(boolean success, String errorCode, String traceId) {
        }

        /** Deliberately has no toString so one-shot test credentials cannot reach assertion output. */
        private static final class McpSurface {
            private final LoopbackBridge bridge;
            private final String apiKey;
            private final String toolName;

            private McpSurface(LoopbackBridge bridge, String apiKey, String toolName) {
                this.bridge = bridge;
                this.apiKey = apiKey;
                this.toolName = toolName;
            }
        }

        private void startRuntime(SqlSessionTemplate session) throws IOException {
            RuntimeWorkflowDefinitionMapper definitionsMapper = session.getMapper(RuntimeWorkflowDefinitionMapper.class);
            workflowVersions = session.getMapper(RuntimeWorkflowVersionMapper.class);
            RuntimeWorkflowReleaseEventMapper releaseEvents = session.getMapper(RuntimeWorkflowReleaseEventMapper.class);
            RuntimeWorkflowReferenceMapper referencesMapper = session.getMapper(RuntimeWorkflowReferenceMapper.class);
            RuntimeWorkflowResourceBindingMapper resourceBindingsMapper =
                    session.getMapper(RuntimeWorkflowResourceBindingMapper.class);

            workflowReferences = transactional(new RuntimeWorkflowReferenceIndex(
                    referencesMapper, definitionsMapper, workflowVersions, json));
            RuntimeWorkflowResourceBindingService resourceBindings = transactional(
                    new RuntimeWorkflowResourceBindingService(resourceBindingsMapper));
            RuntimeWorkflowDefinitionService definitions = transactional(new RuntimeWorkflowDefinitionService(
                    definitionsMapper, workflowVersions, workflowIds -> Set.of(),
                    new RuntimeWorkflowDocumentCanonicalizer(json), resourceBindings, workflowReferences));
            RuntimeWorkflowReleaseValidationService validation = new RuntimeWorkflowReleaseValidationService(
                    null, json, new com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityRegistry(),
                    resourceBindings);
            RuntimeCapabilityCatalogGateway capabilityGateway = new RuntimeCapabilityCatalogGateway(
                    new LoopbackRuntimeCapabilityTransport(capabilityBridge.baseUrl(), json),
                    new RuntimeCapabilityInternalAuthSigner(INTERNAL_SECRET), json);
            RuntimeWorkflowCredentialService workflowCredentials = new RuntimeWorkflowCredentialService(
                    session.getMapper(RuntimeWorkflowCredentialMapper.class),
                    new RuntimeWorkflowCredentialCipher(INTERNAL_SECRET), json);
            WorkflowHttpEgressPolicy egress = WorkflowHttpEgressPolicy.permissiveForTests();
            WorkflowHttpClient httpClient = new WorkflowHttpClient(json, egress, workflowCredentials);
            httpApiConnections = new RuntimeHttpApiConnectionService(
                    session.getMapper(RuntimeHttpApiConnectionMapper.class), capabilityGateway,
                    workflowCredentials, egress);
            httpApiConnections.setVerification(new com.enterprise.ai.runtime.runops.consolehttpapi.RuntimeHttpApiVerificationService(
                    session.getMapper(ConsoleCapabilityInvocationMapper.class), session.getMapper(RuntimeRunMapper.class), json));
            RuntimeWorkflowHttpApiService apiProjection = new RuntimeWorkflowHttpApiService(
                    capabilityGateway, httpApiConnections, session.getMapper(RuntimeWorkflowHttpApiPinMapper.class),
                    httpClient, json);
            RuntimeCapabilityContractPins pins = new RuntimeCapabilityContractPins(capabilityGateway, json,
                    apiProjection);
            RuntimeWorkflowVersionService versions = transactional(new RuntimeWorkflowVersionService(
                    workflowVersions, definitions, validation, json, pins, releaseEvents, workflowReferences));
            RuntimeWorkflowManagementService management = transactional(new RuntimeWorkflowManagementService(
                    definitions, versions, validation));
            RuntimeWorkflowStudioService studio = transactional(new RuntimeWorkflowStudioService(management, versions, json));
            runtimeGraphExecutor = new RuntimeGraphSpecExecutor(json, null, capabilityGateway, null,
                    null, httpClient, null, apiProjection);

            RuntimeTraceSpanMapper traceSpans = session.getMapper(RuntimeTraceSpanMapper.class);
            RuntimeTraceRootService traceRoots = transactional(new RuntimeTraceRootService(traceSpans, json));
            RuntimeHttpApiInvocationService consoleApiCalls = new RuntimeHttpApiInvocationService(
                    session.getMapper(ConsoleCapabilityInvocationMapper.class), session.getMapper(RuntimeRunMapper.class),
                    traceRoots, httpApiConnections, httpClient, json,
                    new DataSourceTransactionManager(database.jdbc().getDataSource()));
            var consoleMethodCalls = new com.enterprise.ai.runtime.runops.consolecapability.ConsoleCapabilityInvocationService(
                    session.getMapper(ConsoleCapabilityInvocationMapper.class), session.getMapper(RuntimeRunMapper.class),
                    traceRoots, capabilityGateway, json, new DataSourceTransactionManager(database.jdbc().getDataSource()));
            RuntimeTraceQueryService traceQuery = new RuntimeTraceQueryService(
                    session.getMapper(RuntimeToolCallLogMapper.class), traceSpans, json);
            runOpsQuery = new RuntimeRunOpsQueryService(
                    session.getMapper(RuntimeRunMapper.class), traceQuery,
                    session.getMapper(RuntimeGuardDecisionLogMapper.class), json);
            RuntimeTraceEvidenceWriter traceEvidence = new RuntimeTraceEvidenceWriter(
                    traceSpans, session.getMapper(RuntimeToolCallLogMapper.class));
            RuntimeRunLifecycleService runs = new RuntimeRunLifecycleService(session.getMapper(RuntimeRunMapper.class), json);
            RuntimeTraceSpanTerminationService spanTermination = transactional(
                    new RuntimeTraceSpanTerminationService(traceSpans));
            RuntimeWorkflowDebugService debug = transactional(new RuntimeWorkflowDebugService(
                    definitions, runtimeGraphExecutor, runs, traceEvidence, json,
                    new RuntimeWorkflowDocumentCanonicalizer(json), traceRoots, spanTermination));
            RuntimeWorkflowReadOnlyTrialService readOnlyTrials = new RuntimeWorkflowReadOnlyTrialService(
                    definitions, apiProjection, capabilityGateway, debug, json);
            RuntimePublishedWorkflowSnapshotReader publishedSnapshots = transactional(
                    new RuntimePublishedWorkflowSnapshotReader(workflowVersions));
            ExecutorService mcpExecutor = Executors.newSingleThreadExecutor();
            ScheduledExecutorService mcpTimeouts = Executors.newSingleThreadScheduledExecutor();
            closers.add(() -> {
                mcpExecutor.shutdownNow();
                mcpTimeouts.shutdownNow();
            });
            RuntimeMcpToolExecutionService mcpExecution = new RuntimeMcpToolExecutionService(
                    capabilityGateway, runtimeGraphExecutor, publishedSnapshots, traceRoots, traceEvidence,
                    runs, json, mcpExecutor, mcpTimeouts);
            RuntimeAgentWorkflowUsageReader agentUsage = transactional(new RuntimeAgentWorkflowUsageReader(
                    session.getMapper(RuntimeAgentMapper.class), session.getMapper(RuntimeAgentWorkflowToolMapper.class),
                    session.getMapper(RuntimeAgentConfigVersionMapper.class)));
            RuntimeCapabilityReferenceService capabilityReferences = transactional(
                    new RuntimeCapabilityReferenceService(workflowReferences, agentUsage));
            writeWorkflowAgent = new WriteWorkflowAgentFixture(session, database.jdbc().getDataSource(), json,
                    runtimeGraphExecutor, traceEvidence, traceRoots, spanTermination, runs, apiProjection);

            InternalServiceAuthProperties runtimeAuth = new InternalServiceAuthProperties(
                    INTERNAL_SECRET, 300, 600, 10_000, 1_048_576);
            InternalServiceAuthFilter runtimeFilter = new InternalServiceAuthFilter(
                    new InternalServiceAuthVerifier(runtimeAuth,
                            new JdbcInternalAuthNonceStore(database.jdbc())), runtimeAuth);
            MockMvc runtimeMvc = MockMvcBuilders.standaloneSetup(
                            writeWorkflowAgent.identities, writeWorkflowAgent.configurations, writeWorkflowAgent.executions,
                            new RuntimeWorkflowPublicController(management, studio, debug, null, null),
                            new RuntimeWorkflowVersionPublicController(management),
                            new RuntimeWorkflowCredentialPublicController(workflowCredentials),
                            new RuntimePublicController(traceQuery, null, null, runOpsQuery,
                                    null, null, 8_000L),
                            new McpToolExecutionInternalController(mcpExecution),
                            new RuntimeCapabilityReferenceInternalController(capabilityReferences),
                            new RuntimeWorkflowReadOnlyTrialInternalController(readOnlyTrials),
                            new com.enterprise.ai.runtime.runops.consolecapability.ConsoleCapabilityInvocationInternalController(consoleMethodCalls),
                            new RuntimeHttpApiInvocationInternalController(consoleApiCalls),
                            new RuntimeHttpApiCatalogStateInternalController(new RuntimeHttpApiCatalogStateService(
                                    session.getMapper(RuntimeHttpApiConnectionMapper.class), session.getMapper(ConsoleCapabilityInvocationMapper.class))),
                            new RuntimeHttpApiConnectionInternalController(httpApiConnections))
                    .addFilters(runtimeFilter)
                    .setControllerAdvice(new RuntimeWorkflowRevisionExceptionHandler())
                    .build();
            runtimeBridge = LoopbackBridge.start(runtimeMvc);
            closers.add(runtimeBridge);
        }

        private void assertInitialReadOnlyDeclarationsWereAutoAccepted() {
            Integer ready = database.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM capability_source_state WHERE project_code = ? AND availability = 'READY'",
                    Integer.class, PROJECT_CODE);
            Integer automatic = database.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM capability_diff_item WHERE project_id = ? AND review_status = 'AUTO_APPLIED'",
                    Integer.class, projectId());
            assertEquals(3, ready, "owner source state must confirm the actual accepted contracts");
            assertEquals(3, automatic, "complete READ_ONLY declarations use the current automatic acceptance policy");
        }

        private void acceptAllPendingSourceChanges() {
            acceptLatestPendingSourceChanges();
        }

        private void acceptLatestPendingSourceChanges() {
            List<com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySnapshotDTO> snapshots =
                    registry.listSnapshots(PROJECT_CODE);
            assertFalse(snapshots.isEmpty(), "SDK source report must create a source snapshot");
            Long snapshotId = snapshots.stream().map(com.enterprise.ai.agent.registry.RegistryContracts.CapabilitySnapshotDTO::id)
                    .filter(java.util.Objects::nonNull).max(Long::compareTo)
                    .orElseThrow(() -> new AssertionError("SDK source snapshot is missing its id"));
            List<com.enterprise.ai.agent.registry.RegistryContracts.CapabilityDiffItemDTO> changes =
                    registry.listDiffItems(PROJECT_CODE, snapshotId);
            List<com.enterprise.ai.agent.registry.RegistryContracts.CapabilityDiffItemDTO> pending = changes.stream()
                    .filter(change -> "PENDING".equalsIgnoreCase(change.reviewStatus())).toList();
            assertFalse(pending.isEmpty(), "the latest source change must remain pending before explicit acceptance");
            for (com.enterprise.ai.agent.registry.RegistryContracts.CapabilityDiffItemDTO change : pending) {
                var response = reviewGateway.reviewDiffItem(PROJECT_CODE, change.id(), Map.of(
                        "action", "APPLY", "operator", "bmapi-fixture", "note", "isolated acceptance"), "1");
                assertTrue(response.getStatusCode().is2xxSuccessful(), "acceptance must enter the production review endpoint");
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> publicBusinessMethods() throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(controlBridge.baseUrl()
                            + "/api/business-methods?current=1&size=20&enabled=true&projectId=" + projectId()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + platformBearer)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode(), new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
            return json.readValue(response.body(), new TypeReference<>() { });
        }

        private Map<String, Object> publicBusinessMethodReferences(String storageName) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(controlBridge.baseUrl()
                            + "/api/capability-review/projects/" + PROJECT_CODE + "/capabilities/"
                            + storageName + "/references"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + platformBearer)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode(), new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
            return json.readValue(response.body(), new TypeReference<>() { });
        }

        private Map<String, Object> publicApiReferences() throws Exception {
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(controlBridge.baseUrl() + "/api/apis/1/references"))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + platformBearer)
                            .header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode(), controlBridge.lastResponseSummary());
            return json.readValue(response.body(), new TypeReference<>() { });
        }

        private Long projectId() {
            return database.jdbc().queryForObject("SELECT id FROM capability_scan_project WHERE project_code = ?",
                    Long.class, PROJECT_CODE);
        }

        private MockMvc controlMvc(SqlSessionTemplate session) {
            PlatformAuthorizationService authorization = new PlatformAuthorizationService(
                    session.getMapper(PlatformUserRoleMapper.class), session.getMapper(PlatformRoleMapper.class),
                    session.getMapper(PlatformRolePermissionMapper.class), session.getMapper(PlatformPermissionMapper.class));
            PlatformRequestAuthorization requestAuthorization = new PlatformRequestAuthorization(authorization);
            PlatformSessionTokenCodec tokenCodec = new PlatformSessionTokenCodec();
            PlatformBearerAuthService bearerAuth = new PlatformBearerAuthService(
                    session.getMapper(PlatformUserMapper.class), session.getMapper(PlatformLoginSessionMapper.class),
                    tokenCodec, authorization);
            PlatformAuthProperties authProperties = new PlatformAuthProperties();
            authProperties.getSessionCookie().setSecure(
                    PlatformAuthProperties.SecureMode.NEVER);
            PlatformSessionCookieService sessionCookies = new PlatformSessionCookieService(authProperties);
            PlatformConsoleAuthInterceptor interceptor = new PlatformConsoleAuthInterceptor(
                    bearerAuth, sessionCookies);
            PlatformIdentityController identity = new PlatformIdentityController(
                    session.getMapper(PlatformUserMapper.class),
                    session.getMapper(PlatformRoleMapper.class),
                    session.getMapper(PlatformUserRoleMapper.class),
                    session.getMapper(PlatformLoginSessionMapper.class),
                    session.getMapper(PlatformAuthProviderMapper.class),
                    authProperties,
                    new PlatformConsoleAuthAvailability(authProperties,
                            session.getMapper(PlatformAuthProviderMapper.class)),
                    authorization,
                    requestAuthorization,
                    new PlatformAuthAuditService(session.getMapper(PlatformAuthAuditEventMapper.class), json),
                    tokenCodec,
                    sessionCookies,
                    new PlatformPasswordConfiguration().platformPasswordEncoder());
            CapabilityCatalogConsoleController controller = new CapabilityCatalogConsoleController(
                    reviewGateway, requestAuthorization);
            CapabilityChangeImpactService impact = new CapabilityChangeImpactService(runtimeProxy(),
                    new InternalServiceAuthSigner(INTERNAL_SECRET), json,
                    new McpPublishedReferenceReader(session.getMapper(McpPublicationMapper.class),
                            session.getMapper(McpPublicationRevisionMapper.class), json),
                    new A2aPublishedReferenceReader(session.getMapper(A2aPublicationMapper.class),
                            session.getMapper(A2aPublicationRevisionMapper.class)));
            CapabilityReviewConsoleController review = new CapabilityReviewConsoleController(
                    reviewGateway, requestAuthorization, impact);
            ControlRuntimePublicController runtime = new ControlRuntimePublicController(
                    runtimeProxy(), null, null,
                    new com.enterprise.ai.control.runtime.RuntimeTrustedAgentExecutionGateway(
                            new InternalServiceAuthSigner(INTERNAL_SECRET), json, null, runtimeBridge.baseUrl()), bearerAuth);
            Object runtimeAccess = configureRuntimeControlSecurity(runtime, requestAuthorization);
            ControlToolAclDecisionService toolAcl = new ControlToolAclDecisionService(
                    session.getMapper(ControlToolAclMapper.class));
            ControlWorkflowReadOnlyTrialController readOnlyTrials;
            try {
                var constructor = ControlWorkflowReadOnlyTrialController.class.getDeclaredConstructor(
                        RuntimeProxyClient.class, CapabilityReviewGateway.class, runtimeAccess.getClass(),
                        ControlToolAclDecisionService.class, ControlWorkflowReadOnlyTrialGateway.class,
                        ObjectMapper.class);
                constructor.setAccessible(true);
                readOnlyTrials = constructor.newInstance(runtimeProxy(), reviewGateway, runtimeAccess,
                        toolAcl, new ControlWorkflowReadOnlyTrialGateway(runtimeProxy(),
                                new InternalServiceAuthSigner(INTERNAL_SECRET), json), json);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("isolated Control trial wiring failed", failure);
            }
            HttpApiConsoleController httpApis = new HttpApiConsoleController(
                    reviewGateway, new RuntimeHttpApiConsoleGateway(runtimeProxy(),
                            new InternalServiceAuthSigner(INTERNAL_SECRET), json), requestAuthorization,
                    toolAcl, json);
            CapabilityCompatibilityProxyController capabilityProxy = new CapabilityCompatibilityProxyController(
                    new RestTemplateBuilder(), capabilityBridge.baseUrl());
            var publicationRepository = new MybatisMcpPublicationRepository(
                    session.getMapper(McpPublicationMapper.class), session.getMapper(McpPublicationItemMapper.class),
                    session.getMapper(McpPublicationRevisionMapper.class), json);
            var clientRepository = new MybatisMcpClientRepository(session.getMapper(McpClientMapper.class), json);
            var contracts = new CompositeMcpItemContractResolver(List.of(new WorkflowItemContractResolver(runtimeProxy(), json)));
            var publications = transactional(new McpPublicationApplicationService(publicationRepository, clientRepository,
                    new McpPrecheckService(publicationRepository, contracts), contracts, json));
            var clients = transactional(new McpClientApplicationService(clientRepository, publications, toolAcl));
            var mcpProperties = new McpHubProperties();
            var protocol = new McpProtocolApplicationService(clientRepository, publicationRepository,
                    new MybatisMcpCallAuditRepository(session.getMapper(McpCallLogMapper.class)), toolAcl,
                    new TrustedMcpRuntimeExecutionGateway(new InternalServiceAuthSigner(INTERNAL_SECRET), json,
                            mcpProperties, runtimeBridge.baseUrl()), json);
            var managementAccess = new McpHubManagementAccess();
            var audit = new PlatformAuthAuditService(session.getMapper(PlatformAuthAuditEventMapper.class), json);
            return MockMvcBuilders.standaloneSetup(identity, controller, review, runtime, readOnlyTrials,
                            capabilityProxy, httpApis,
                            new com.enterprise.ai.control.capability.ApiMarketConsoleController(reviewGateway, requestAuthorization),
                            new McpPublicationController(publications, managementAccess, audit),
                            new McpClientController(clients, managementAccess, audit),
                            new McpEndpointController(new McpProtocolRequestGuard(mcpProperties), protocol,
                                    mcpProperties, json, new McpAuditPayloadSanitizer(json, mcpProperties)),
                            new com.enterprise.ai.control.capability.CapabilityInvocationConsoleController(
                                    reviewGateway, new com.enterprise.ai.control.capability.RuntimeConsoleCapabilityInvocationGateway(
                                            runtimeProxy(), new InternalServiceAuthSigner(INTERNAL_SECRET), json),
                                    requestAuthorization, toolAcl, json),
                            new HttpApiReferenceController(reviewGateway, requestAuthorization, impact))
                    .addInterceptors(new FixturePlatformConsoleAuthBoundary(interceptor,
                            new com.enterprise.ai.control.identity.PlatformOptionalSessionAuthInterceptor(interceptor, sessionCookies)))
                    .setControllerAdvice(new McpProtocolExceptionHandler(protocol), new McpHubManagementExceptionHandler())
                    .build();
        }

        private Object configureRuntimeControlSecurity(
                ControlRuntimePublicController controller,
                PlatformRequestAuthorization requestAuthorization) {
            CapabilityProjectOnboardingClient projects = (CapabilityProjectOnboardingClient) Proxy.newProxyInstance(
                    CapabilityProjectOnboardingClient.class.getClassLoader(),
                    new Class<?>[]{CapabilityProjectOnboardingClient.class}, (proxy, method, arguments) -> {
                        if ("getProjectById".equals(method.getName())) {
                            ResponseEntity<Object> response = reviewGateway.getProjectById(
                                    (Long) arguments[0], String.valueOf(platformUserId));
                            if (response == null || !response.getStatusCode().is2xxSuccessful()
                                    || !(response.getBody() instanceof Map<?, ?> body)) {
                                throw new IllegalStateException("isolated Capability project lookup failed");
                            }
                            Map<String, Object> copy = new LinkedHashMap<>();
                            body.forEach((key, value) -> copy.put(String.valueOf(key), value));
                            return copy;
                        }
                        if ("toString".equals(method.getName())) return "bmapi-2d-capability-project-proxy";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == arguments[0];
                        throw new UnsupportedOperationException("unexpected Capability project proxy call "
                                + method.getName());
                    });
            try {
                Class<?> accessType = Class.forName("com.enterprise.ai.control.runtime.RuntimeManagementAccess");
                var constructor = accessType.getDeclaredConstructor(
                        PlatformRequestAuthorization.class, CapabilityProjectOnboardingClient.class);
                constructor.setAccessible(true);
                Object access = constructor.newInstance(requestAuthorization, projects);
                setPrivateField(controller, "runtimeManagementAccess", access);
                setPrivateField(controller, "workflowReleaseGateway", new RuntimeWorkflowReleaseGateway(
                        runtimeProxy(), new InternalServiceAuthSigner(INTERNAL_SECRET), json));
                return access;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("isolated Control Runtime security wiring failed", failure);
            }
        }

        private static void setPrivateField(Object target, String fieldName, Object value)
                throws ReflectiveOperationException {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        }

        private static final class FixturePlatformConsoleAuthBoundary implements HandlerInterceptor {
            private final PlatformConsoleAuthInterceptor delegate;
            private final HandlerInterceptor optional;

            private FixturePlatformConsoleAuthBoundary(PlatformConsoleAuthInterceptor delegate, HandlerInterceptor optional) {
                this.delegate = delegate;
                this.optional = optional;
            }

            @Override
            public boolean preHandle(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    Object handler) throws Exception {
                if (PlatformConsoleRoutePolicy.OPTIONAL_SESSION_PATH_PATTERNS.stream().anyMatch(
                        pattern -> new org.springframework.util.AntPathMatcher().match(pattern, request.getRequestURI()))) {
                    return optional.preHandle(request, response, handler);
                }
                if (PlatformConsoleRoutePolicy.credentialDomainFor(request.getRequestURI())
                        != PlatformConsoleRoutePolicy.CredentialDomain.PLATFORM_SESSION) {
                    return true;
                }
                return delegate.preHandle(request, response, handler);
            }
        }

        private void configureRealPlatformSession(SqlSessionTemplate session) {
            PlatformUserMapper users = session.getMapper(PlatformUserMapper.class);
            PlatformRoleMapper roles = session.getMapper(PlatformRoleMapper.class);
            PlatformPermissionMapper permissions = session.getMapper(PlatformPermissionMapper.class);
            PlatformUserRoleMapper userRoles = session.getMapper(PlatformUserRoleMapper.class);
            PlatformRolePermissionMapper rolePermissions = session.getMapper(PlatformRolePermissionMapper.class);
            PlatformLoginSessionMapper sessions = session.getMapper(PlatformLoginSessionMapper.class);
            PlatformAuthProviderMapper providers = session.getMapper(PlatformAuthProviderMapper.class);
            PlatformUserEntity user = new PlatformUserEntity();
            user.setUsername(PlatformAuthProperties.DEVELOPMENT_ADMIN_USERNAME); user.setDisplayName("BMAPI Fixture");
            user.setPasswordHash("{plain}" + PlatformAuthProperties.DEVELOPMENT_ADMIN_PASSWORD);
            user.setStatus("ACTIVE"); user.setSourceProvider("LOCAL"); user.setExternalSubject("local:bmapi-fixture");
            user.setCreatedAt(LocalDateTime.now()); user.setUpdatedAt(LocalDateTime.now()); users.insert(user);
            platformUserId = user.getId();
            PlatformRoleEntity role = new PlatformRoleEntity();
            role.setRoleCode("bmapi-fixture-role"); role.setRoleName("BMAPI fixture role");
            role.setRoleKind("SYSTEM"); role.setStatus("ACTIVE");
            role.setCreatedAt(LocalDateTime.now()); role.setUpdatedAt(LocalDateTime.now()); roles.insert(role);
            for (String permissionCode : List.of(
                    PlatformPermissions.PLATFORM_READ,
                    PlatformPermissions.WORKFLOW_READ,
                    PlatformPermissions.WORKFLOW_WRITE,
                    PlatformPermissions.WORKFLOW_PUBLISH,
                    PlatformPermissions.RUNOPS_READ,
                    PlatformPermissions.AGENT_READ,
                    PlatformPermissions.AGENT_WRITE,
                    PlatformPermissions.AGENT_PUBLISH,
                    PlatformPermissions.WORKFLOW_CREDENTIAL_MANAGE)) {
                PlatformPermissionEntity permission = new PlatformPermissionEntity();
                permission.setPermissionCode(permissionCode); permission.setPermissionName(permissionCode);
                permission.setResourceType("PROJECT"); permission.setAction("ACCESS"); permissions.insert(permission);
                PlatformRolePermissionEntity rolePermission = new PlatformRolePermissionEntity();
                rolePermission.setRoleId(role.getId()); rolePermission.setPermissionId(permission.getId());
                rolePermissions.insert(rolePermission);
            }
            PlatformUserRoleEntity userRole = new PlatformUserRoleEntity();
            userRole.setUserId(user.getId()); userRole.setRoleId(role.getId());
            userRole.setScopeType("PROJECT"); userRole.setScopeValue(PROJECT_CODE); userRole.setCreatedAt(LocalDateTime.now());
            userRoles.insert(userRole);
            PlatformAuthProviderEntity localProvider = new PlatformAuthProviderEntity();
            localProvider.setProviderCode("LOCAL"); localProvider.setProviderName("Isolated local login");
            localProvider.setProviderType("LOCAL"); localProvider.setConfigJson("{}"); localProvider.setStatus("ACTIVE");
            localProvider.setCreatedAt(LocalDateTime.now()); localProvider.setUpdatedAt(LocalDateTime.now());
            providers.insert(localProvider);
            PlatformSessionTokenCodec tokenCodec = new PlatformSessionTokenCodec();
            platformBearer = tokenCodec.issueRawToken();
            PlatformLoginSessionEntity login = new PlatformLoginSessionEntity();
            login.setSessionId("bmapi-2d-session"); login.setUserId(user.getId()); login.setProvider("LOCAL");
            login.setAccessTokenId(tokenCodec.digest(platformBearer)); login.setIp("127.0.0.1");
            login.setUserAgent("bmapi-2d-test"); login.setExpiresAt(LocalDateTime.now().plusMinutes(30));
            login.setCreatedAt(LocalDateTime.now()); sessions.insert(login);
        }

        @Override
        public void close() throws Exception {
            Exception failure = null;
            for (int index = closers.size() - 1; index >= 0; index--) {
                try {
                    closers.get(index).close();
                } catch (Exception closeFailure) {
                    if (failure == null) failure = closeFailure;
                }
            }
            if (transactions != null) {
                transactions.close();
            }
            if (failure != null) throw failure;
        }

        private static List<String> tables() {
            return List.of(
                    "capability_scan_project", "capability_scan_project_tool", "capability_tool_definition",
                    "capability_project_instance", "capability_sync_log", "capability_snapshot",
                    "capability_sync_receipt", "capability_diff_item", "capability_apply_record",
                    "capability_source_state", "capability_business_method_asset", "capability_business_method_revision",
                    "capability_registry_project_credential",
                    "capability_registry_enrollment_token", "capability_registry_request_nonce",
                    "capability_internal_auth_nonce",
                    "control_platform_user", "control_platform_role", "control_platform_user_role",
                    "control_platform_permission", "control_platform_role_permission", "control_platform_login_session",
                    "runtime_internal_auth_nonce", "runtime_workflow", "runtime_workflow_version",
                    "runtime_workflow_release_event",
                    "runtime_workflow_resource_binding", "runtime_run", "runtime_trace_span", "runtime_tool_call_log",
                    "runtime_guard_decision_log",
                    "control_tool_acl");
        }

        private static Class<?>[] mapperTypes() {
            return new Class<?>[] {
                    ScanProjectMapper.class, ScanProjectToolMapper.class, ScanModuleMapper.class, SemanticDocMapper.class, ToolDefinitionMapper.class,
                    ProjectInstanceMapper.class, CapabilitySyncLogMapper.class, CapabilitySnapshotMapper.class,
                    CapabilityDiffItemMapper.class, CapabilityApplyRecordMapper.class, CapabilitySourceStateMapper.class,
                    BusinessMethodAssetMapper.class, BusinessMethodRevisionMapper.class,
                    CapabilitySyncReceiptMapper.class, RegistryCredentialMapper.class, RegistryEnrollmentTokenMapper.class,
                    PlatformUserMapper.class, PlatformRoleMapper.class, PlatformUserRoleMapper.class,
                    PlatformPermissionMapper.class, PlatformRolePermissionMapper.class, PlatformLoginSessionMapper.class,
                    PlatformAuthProviderMapper.class, PlatformAuthAuditEventMapper.class,
                    HttpApiAssetMapper.class, HttpApiSourceBindingMapper.class,
                    HttpApiInventoryStateMapper.class, HttpApiInventoryMemberMapper.class,
                    HttpApiAcceptanceMapper.class, RuntimeHttpApiConnectionMapper.class,
                    com.enterprise.ai.capability.externalapi.ExternalApiSourceMapper.class,
                    com.enterprise.ai.capability.externalapi.ExternalApiProviderMapper.class,
                    com.enterprise.ai.capability.externalapi.ExternalApiEntryMapper.class,
                    com.enterprise.ai.capability.externalapi.ExternalApiVersionMapper.class,
                    com.enterprise.ai.capability.externalapi.ExternalApiOperationMapper.class,
                    com.enterprise.ai.capability.externalapi.ExternalApiVerificationMapper.class,
                    com.enterprise.ai.capability.externalapi.ProjectExternalApiMapper.class,
                    com.enterprise.ai.capability.externalapi.ProjectExternalApiOperationMapper.class,
                    ConsoleCapabilityInvocationMapper.class,
                    RuntimeWorkflowHttpApiPinMapper.class,
                    RuntimeWorkflowDefinitionMapper.class, RuntimeWorkflowVersionMapper.class,
                    RuntimeWorkflowReleaseEventMapper.class, RuntimeWorkflowReferenceMapper.class,
                    RuntimeWorkflowResourceBindingMapper.class, RuntimeRunMapper.class,
                    RuntimeGuardDecisionLogMapper.class, RuntimeTraceSpanMapper.class,
                    RuntimeToolCallLogMapper.class, RuntimeAgentMapper.class, RuntimeAgentConfigVersionMapper.class,
                    RuntimeAgentWorkflowToolMapper.class, RuntimeWorkflowCredentialMapper.class,
                    com.enterprise.ai.runtime.agent.RuntimeAgentSkillBindingMapper.class,
                    com.enterprise.ai.runtime.agent.RuntimeAgentRemoteBindingMapper.class,
                    com.enterprise.ai.runtime.execution.RuntimeInteractionSessionMapper.class,
                    com.enterprise.ai.runtime.execution.RuntimeInteractionEventMapper.class,
                    ControlToolAclMapper.class, McpPublicationMapper.class,
                    McpPublicationItemMapper.class, McpPublicationRevisionMapper.class, McpClientMapper.class,
                    McpCallLogMapper.class, A2aPublicationMapper.class, A2aPublicationRevisionMapper.class
            };
        }

        @SuppressWarnings("unchecked")
        private <T> T transactional(T target) {
            ProxyFactory proxy = new ProxyFactory(target);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(
                    new DataSourceTransactionManager(database.jdbc().getDataSource()),
                    new AnnotationTransactionAttributeSource()));
            return (T) proxy.getProxy();
        }

        private void createH2Table(String tableName) throws Exception {
            String baseline = Files.readString(Path.of("../sql/initV2.sql"));
            var table = java.util.regex.Pattern.compile(
                    "(?ism)CREATE TABLE IF NOT EXISTS `" + tableName + "`\\s*\\(.*?^\\)\\s*ENGINE=[^\\r\\n]*;")
                    .matcher(baseline);
            assertTrue(table.find(), tableName + " must remain present in initV2.sql");
            database.jdbc().execute(table.group()
                    .replaceAll("(?im)^\\)\\s*ENGINE=[^\\r\\n]*;", ");")
                    .replaceAll("(?i)COLLATE \\w+", "")
                    .replace("`reference_key`(191)", "`reference_key`")
                    .replaceAll("(?i)(UNIQUE\\s+)?KEY\\s+`([^`]+)`",
                            "$1KEY `" + tableName + "_$2`"));
        }

        private static SqlSessionTemplate session(RuntimeQueryTestDatabase database) throws Exception {
            java.lang.reflect.Field field = RuntimeQueryTestDatabase.class.getDeclaredField("session");
            field.setAccessible(true);
            return (SqlSessionTemplate) field.get(database);
        }
    }

    /** Public-safe launcher handoff: contains topology and stable identifiers, never credentials or signatures. */
    static record BrowserSurface(
            String controlOrigin,
            String workflowId,
            Long projectId,
            String projectCode,
            List<String> qualifiedNames) {
    }

    static record ApiBrowserSurface(String controlOrigin, String workflowId, Long projectId,
                                    String projectCode, Long apiId, String authStatePath) {
    }

    static record MultiSourceBrowserSurface(String controlOrigin, String workflowId, String scanPath,
                                           String specFile, String upstreamOrigin, String authStatePath) { }
    static record WriteApiBrowserSurface(String controlOrigin, String scanPath, String specFile,
                                         String upstreamOrigin, String credentialRef, String authStatePath) { }

    static record ApiBrowserOutcome(long versionId, String version, String runId,
                                    String traceId, int controllerMethodCalls) {
    }

    static record MethodBrowserSurface(String controlOrigin, String workflowId, Long projectId,
                                       String projectCode, String methodName, String qualifiedName, String authStatePath) { }

    static final class LoopbackBridge implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService bridgeExecutor = Executors.newCachedThreadPool();
        private final AtomicReference<String> lastResponse = new AtomicReference<>("none");
        // Fixture-only observation of the already-redacted trial DTO; never captures headers or credentials.
        private final AtomicReference<byte[]> readOnlyTrialResponse = new AtomicReference<>();
        private final AtomicReference<String> lastIdentitySource = new AtomicReference<>();
        private final AtomicInteger requests = new AtomicInteger();
        private final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> routeRequests = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicBoolean dropResponseAfterEndpoint = new java.util.concurrent.atomic.AtomicBoolean();
        int requestCount() { return requests.get(); }
        int requestsFor(String method, String path) { return routeRequests.getOrDefault(method + " " + path, new AtomicInteger()).get(); }
        int requestsForPrefix(String method, String prefix) {
            return routeRequests.entrySet().stream().filter(entry -> entry.getKey().startsWith(method + " " + prefix))
                    .mapToInt(entry -> entry.getValue().get()).sum();
        }
        void dropNextResponseAfterEndpoint() { dropResponseAfterEndpoint.set(true); }

        private LoopbackBridge(HttpServer server) {
            this.server = server;
        }

        static LoopbackBridge start(MockMvc mvc) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            LoopbackBridge bridge = new LoopbackBridge(server);
            server.createContext("/", exchange -> bridge.forward(mvc, exchange));
            server.setExecutor(bridge.bridgeExecutor);
            server.start();
            return bridge;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String lastResponseSummary() {
            return lastResponse.get();
        }

        byte[] lastReadOnlyTrialResponse() {
            return java.util.Objects.requireNonNull(readOnlyTrialResponse.get(), "actual read-only trial HTTP response missing").clone();
        }

        String lastIdentitySource() {
            return lastIdentitySource.get();
        }

        @Override
        public void close() {
            server.stop(0);
            bridgeExecutor.shutdownNow();
            readOnlyTrialResponse.set(null);
        }

        private void forward(MockMvc mvc, HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            routeRequests.computeIfAbsent(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath(), ignored -> new AtomicInteger()).incrementAndGet();
            try {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                URI uri = URI.create(exchange.getRequestURI().toString());
                MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(
                                HttpMethod.valueOf(exchange.getRequestMethod()), uri)
                        .remoteAddress("127.0.0.1").content(requestBody);
                exchange.getRequestHeaders().forEach((name, values) -> {
                    if ("Cookie".equalsIgnoreCase(name)) {
                        // MockMvc does not derive HttpServletRequest#getCookies() from a
                        // raw Cookie header.  Preserve the actual browser cookies when
                        // adapting the loopback HTTP request to the production interceptor.
                        values.forEach(value -> addBrowserCookies(request, value));
                    } else if ("Accept".equalsIgnoreCase(name)
                            && values.stream().allMatch(value -> "*/*".equals(value.trim()))) {
                        // The production JSON API defaults to JSON. MockMvc's optional XML converter
                        // otherwise wins a browser fetch with Accept: */* during session bootstrap.
                        request.header("Accept", "application/json");
                    } else {
                        values.forEach(value -> request.header(name, value));
                    }
                });
                lastIdentitySource.set(exchange.getRequestHeaders().getFirst(InternalServiceAuthHeaders.IDENTITY_SOURCE));
                MvcResult result = mvc.perform(request).andReturn();
                if (dropResponseAfterEndpoint.compareAndSet(true, false)) {
                    lastResponse.set("injected response loss after production endpoint completed");
                    return;
                }
                MockHttpServletResponse response = result.getResponse();
                response.getHeaderNames().forEach(name -> {
                    for (String value : response.getHeaders(name)) {
                        exchange.getResponseHeaders().add(name, value);
                    }
                });
                byte[] responseBody = response.getContentAsByteArray();
                if ("POST".equals(exchange.getRequestMethod()) && response.getStatus() == 200
                        && "/api/workflows/studio/read-only-trials".equals(uri.getPath())) {
                    readOnlyTrialResponse.set(responseBody.clone());
                }
                String contentType = response.getContentType();
                String rendered = new String(responseBody, java.nio.charset.StandardCharsets.UTF_8)
                        .replace('\r', ' ').replace('\n', ' ');
                lastResponse.set("status=" + response.getStatus() + ",contentType=" + contentType
                        + ",body=" + (rendered.length() > 600 ? rendered.substring(0, 600) : rendered));
                exchange.sendResponseHeaders(response.getStatus(), responseBody.length);
                exchange.getResponseBody().write(responseBody);
            } catch (Exception failure) {
                Throwable root = failure;
                while (root.getCause() != null) root = root.getCause();
                StackTraceElement at = root.getStackTrace().length == 0 ? null : root.getStackTrace()[0];
                lastResponse.set("bridgeException=" + root.getClass().getSimpleName()
                        + ",at=" + (at == null ? "unknown" : at.getClassName() + ":" + at.getLineNumber()));
                byte[] body = "{\"code\":\"BMAPI_TEST_BRIDGE_FAILURE\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        }

        private static void addBrowserCookies(MockHttpServletRequestBuilder request, String header) {
            if (header == null || header.isBlank()) {
                return;
            }
            for (String pair : header.split(";")) {
                int separator = pair.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String name = pair.substring(0, separator).trim();
                String value = pair.substring(separator + 1).trim();
                if (name.isEmpty()) {
                    continue;
                }
                try {
                    request.cookie(new jakarta.servlet.http.Cookie(name, value));
                } catch (IllegalArgumentException ignored) {
                    // Browser transport should not make a malformed unrelated cookie
                    // appear as a valid servlet cookie in this isolated adapter.
                }
            }
        }
    }

    /** Raw HTTP transport used by Runtime's production gateway; it only adapts Feign to the isolated loopback. */
    static final class LoopbackRuntimeCapabilityTransport implements RuntimeCapabilityCatalogFeignClient {
        private final String baseUrl;
        private final ObjectMapper json;

        private LoopbackRuntimeCapabilityTransport(String baseUrl, ObjectMapper json) {
            this.baseUrl = baseUrl;
            this.json = json;
        }

        @Override
        public Map<String, Object> getToolDefinition(String qualifiedName) {
            try {
                HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/internal/capability/tools/" + qualifiedName))
                                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    var request = feign.Request.create(feign.Request.HttpMethod.GET,
                            baseUrl + "/internal/capability/tools/" + qualifiedName, Map.of(), null,
                            StandardCharsets.UTF_8, null);
                    throw feign.FeignException.errorStatus("getToolDefinition", feign.Response.builder()
                            .status(response.statusCode()).reason("Catalog lookup").request(request).headers(Map.of())
                            .body(response.body()).build());
                }
                return json.readValue(response.body(), new TypeReference<>() { });
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Capability catalog loopback interrupted", interrupted);
            } catch (IOException failure) {
                throw new IllegalStateException("Capability catalog loopback transport failed", failure);
            }
        }

        @Override
        public CapabilityInvocationResponse invokeTool(String qualifiedName,
                                                        Map<String, String> internalAuthHeaders,
                                                        byte[] exactBody) {
            return post("/internal/capability/tools/" + qualifiedName + "/execute", internalAuthHeaders, exactBody);
        }

        @Override
        public CapabilityInvocationResponse invokeCapability(Map<String, String> internalAuthHeaders,
                                                             byte[] exactBody) {
            return post("/internal/capability/invocations", internalAuthHeaders, exactBody);
        }

        @Override
        public Map<String, Object> getProject(String projectCode) {
            throw new UnsupportedOperationException("project lookup is not part of this Runtime graph");
        }

        @Override
        public Map<String, Object> getProjectById(Long projectId) {
            throw new UnsupportedOperationException("project lookup is not part of this Runtime graph");
        }

        @Override
        public List<Map<String, Object>> listProjectTools(Long projectId) {
            throw new UnsupportedOperationException("project tool listing is not part of this Runtime graph");
        }

        @Override
        public Map<String, Object> projectReadinessFacts(Long projectId) {
            throw new UnsupportedOperationException("project readiness is not part of this Runtime graph");
        }

        @Override
        public com.enterprise.ai.common.capability.HttpApiConsoleContracts.ExecutionContext httpApiExecutionContext(
                Long apiId, Map<String, String> headers, byte[] body) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl
                                + "/internal/capability/http-apis/" + apiId + "/execution-context"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
                headers.forEach(request::header);
                HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                        request.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("Capability HTTP API owner returned HTTP " + response.statusCode());
                }
                return json.readValue(response.body(),
                        com.enterprise.ai.common.capability.HttpApiConsoleContracts.ExecutionContext.class);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Capability HTTP API owner loopback interrupted", interrupted);
            } catch (IOException failure) {
                throw new IllegalStateException("Capability HTTP API owner loopback failed", failure);
            }
        }

        @Override
        public com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts.InvocationContext businessMethodExecutionContext(
                String qualifiedName, Map<String, String> headers, byte[] body) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl
                                + "/internal/capability/business-methods/" + qualifiedName + "/execution-context"))
                        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                        .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
                headers.forEach(request::header);
                var response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) throw new IllegalStateException("Business method owner returned HTTP " + response.statusCode());
                return json.readValue(response.body(), com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts.InvocationContext.class);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("Business method owner read interrupted", interrupted);
            } catch (IOException failure) { throw new IllegalStateException("Business method owner read failed", failure); }
        }

        private CapabilityInvocationResponse post(String path,
                                                  Map<String, String> headers,
                                                  byte[] body) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
                if (headers != null) {
                    headers.forEach(request::header);
                }
                HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                        request.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Capability loopback returned HTTP " + response.statusCode());
                }
                return json.readValue(response.body(), CapabilityInvocationResponse.class);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Capability loopback interrupted", interrupted);
            } catch (IOException failure) {
                throw new IllegalStateException("Capability loopback transport failed", failure);
            }
        }
    }

    static final class InvocationEvidence {
        private final AtomicInteger zeroArgumentCalls = new AtomicInteger();
        private final AtomicReference<String> scalarInput = new AtomicReference<>();
        private final AtomicReference<Map<String, Object>> dtoInput = new AtomicReference<>();
        private final AtomicReference<String> signedProject = new AtomicReference<>();
        private final AtomicReference<String> signedTenant = new AtomicReference<>();
        private final AtomicReference<String> businessUser = new AtomicReference<>();
        private final AtomicReference<List<String>> businessRoles = new AtomicReference<>();
    }

    static final class DeterministicBusinessFixture {
        private final AtomicInteger hits;
        private final InvocationEvidence evidence;

        DeterministicBusinessFixture(AtomicInteger hits, InvocationEvidence evidence) {
            this.hits = hits;
            this.evidence = evidence;
        }

        @ReachCapability(name = "health", title = "Health", description = "Deterministic zero-argument health check",
                sideEffect = ReachSideEffectLevel.READ)
        public Map<String, Object> health() {
            hits.incrementAndGet();
            evidence.zeroArgumentCalls.incrementAndGet();
            return Map.of("status", "UP");
        }

        @ReachCapability(name = "normalizeOrderNo", title = "Normalize order number",
                description = "Deterministic scalar normalization", sideEffect = ReachSideEffectLevel.READ)
        public String normalizeOrderNo(@ReachParam(name = "orderNo", required = true) String orderNo) {
            hits.incrementAndGet();
            evidence.scalarInput.set(orderNo);
            return "N-" + orderNo;
        }

        @ReachCapability(name = "queryOrder", title = "Query order", description = "Deterministic DTO query",
                sideEffect = ReachSideEffectLevel.READ)
        public OrderResult queryOrder(@ReachParam(name = "request", required = true) OrderQuery request) {
            hits.incrementAndGet();
            evidence.dtoInput.set(Map.of("orderNo", request.orderNo, "customerId", request.customerId));
            return new OrderResult("ORD-" + request.orderNo, "OPEN");
        }
    }

    /** Same public contracts except for health metadata, used only to submit a real source drift report. */
    static final class DriftBusinessFixture {
        @ReachCapability(name = "health", title = "Health", description = "Drifted zero-argument health check",
                sideEffect = ReachSideEffectLevel.READ)
        public Map<String, Object> health() {
            return Map.of("status", "UP");
        }

        @ReachCapability(name = "normalizeOrderNo", title = "Normalize order number",
                description = "Deterministic scalar normalization", sideEffect = ReachSideEffectLevel.READ)
        public String normalizeOrderNo(@ReachParam(name = "orderNo", required = true) String orderNo) {
            return "N-" + orderNo;
        }

        @ReachCapability(name = "queryOrder", title = "Query order", description = "Deterministic DTO query",
                sideEffect = ReachSideEffectLevel.READ)
        public OrderResult queryOrder(@ReachParam(name = "request", required = true) OrderQuery request) {
            return new OrderResult("ORD-" + request.orderNo, "OPEN");
        }
    }

    /** Deliberately omits health so the owner records a real source removal. */
    static final class RemovedHealthBusinessFixture {
        @ReachCapability(name = "normalizeOrderNo", title = "Normalize order number",
                description = "Deterministic scalar normalization", sideEffect = ReachSideEffectLevel.READ)
        public String normalizeOrderNo(@ReachParam(name = "orderNo", required = true) String orderNo) {
            return "N-" + orderNo;
        }

        @ReachCapability(name = "queryOrder", title = "Query order", description = "Deterministic DTO query",
                sideEffect = ReachSideEffectLevel.READ)
        public OrderResult queryOrder(@ReachParam(name = "request", required = true) OrderQuery request) {
            return new OrderResult("ORD-" + request.orderNo, "OPEN");
        }
    }

    static final class OrderQuery {
        @ReachParam(name = "orderNo", required = true)
        public String orderNo;

        @ReachParam(name = "customerId", required = true)
        public String customerId;
    }

    record OrderResult(String orderId, String status) {
    }
}
