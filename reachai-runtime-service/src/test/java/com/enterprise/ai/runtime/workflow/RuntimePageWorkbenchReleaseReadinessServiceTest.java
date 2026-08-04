package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.workflow.RuntimePageWorkbenchReleaseReadinessService.ReleaseReadinessView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimePageWorkbenchReleaseReadinessServiceTest {

    private RuntimeWorkflowDefinitionMapper workflowMapper;
    private RuntimeWorkflowVersionMapper versionMapper;
    private RuntimeWorkflowResourceBindingService bindingService;
    private RuntimeWorkflowReleaseValidationService validationService;
    private RuntimePageWorkbenchReleaseReadinessService service;

    @BeforeEach
    void setUp() {
        workflowMapper = mock(RuntimeWorkflowDefinitionMapper.class);
        versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        bindingService = mock(RuntimeWorkflowResourceBindingService.class);
        validationService = mock(
                RuntimeWorkflowReleaseValidationService.class);
        service = new RuntimePageWorkbenchReleaseReadinessService(
                workflowMapper,
                versionMapper,
                bindingService,
                validationService);
    }

    @Test
    void passesAValidatedDraftWithAnUnusedTargetVersion() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("DRAFT");
        when(workflowMapper.selectById("wf-orders")).thenReturn(workflow);
        when(bindingService.list("wf-orders")).thenReturn(List.of(
                binding("orders.detail")));
        when(validationService.validate(workflow)).thenReturn(valid());
        when(versionMapper.selectOne(any())).thenReturn(null);
        when(versionMapper.listActive("wf-orders")).thenReturn(List.of());

        ReleaseReadinessView result = service.evaluate(
                "orders",
                "orders.detail",
                "wf-orders",
                "v2.0.0");

        assertEquals("PASS", result.status());
        assertEquals("PRE_RELEASE_READY", result.code());
        assertEquals("VALIDATED_DRAFT", result.releaseState());
        assertEquals(Boolean.TRUE, result.releaseValid());
    }

    @Test
    void passesOnlyTheExactActivePublishedVersionAsPublished() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("ACTIVE");
        RuntimeWorkflowVersionEntity version = version("ACTIVE");
        when(workflowMapper.selectById("wf-orders")).thenReturn(workflow);
        when(bindingService.list("wf-orders")).thenReturn(List.of(
                binding("orders.detail")));
        when(validationService.validate(workflow)).thenReturn(valid());
        when(versionMapper.selectOne(any())).thenReturn(version);

        ReleaseReadinessView result = service.evaluate(
                "orders",
                "orders.detail",
                "wf-orders",
                "v1.0.0");

        assertEquals("PASS", result.status());
        assertEquals("PUBLISHED", result.releaseState());
        assertEquals(21L, result.publishedVersionId());
        assertEquals("v1.0.0", result.publishedVersion());
    }

    @Test
    void failsWhenTheWorkflowDoesNotTargetTheCurrentPage() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("DRAFT");
        when(workflowMapper.selectById("wf-orders")).thenReturn(workflow);
        when(bindingService.list("wf-orders")).thenReturn(List.of(
                binding("orders.list")));

        ReleaseReadinessView result = service.evaluate(
                "orders",
                "orders.detail",
                "wf-orders",
                "v2.0.0");

        assertEquals("FAIL", result.status());
        assertEquals("WORKFLOW_PAGE_MISMATCH", result.code());
    }

    @Test
    void failsWhenPlatformReleaseValidationFails() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("DRAFT");
        when(workflowMapper.selectById("wf-orders")).thenReturn(workflow);
        when(bindingService.list("wf-orders")).thenReturn(List.of(
                binding("orders.detail")));
        when(validationService.validate(workflow)).thenReturn(
                RuntimeWorkflowReleaseValidationResult.builder()
                        .error(
                                "GRAPH_ENTRY_MISSING",
                                null,
                                "GraphSpec entry is required")
                        .build());

        ReleaseReadinessView result = service.evaluate(
                "orders",
                "orders.detail",
                "wf-orders",
                "v2.0.0");

        assertEquals("FAIL", result.status());
        assertEquals("RELEASE_VALIDATION_FAILED", result.code());
        assertEquals(1, result.errors().size());
    }

    @Test
    void failsWhenTheRequestedVersionWasAlreadyRetired() {
        RuntimeWorkflowDefinitionEntity workflow = workflow("ACTIVE");
        when(workflowMapper.selectById("wf-orders")).thenReturn(workflow);
        when(bindingService.list("wf-orders")).thenReturn(List.of(
                binding("orders.detail")));
        when(validationService.validate(workflow)).thenReturn(valid());
        when(versionMapper.selectOne(any())).thenReturn(version("RETIRED"));

        ReleaseReadinessView result = service.evaluate(
                "orders",
                "orders.detail",
                "wf-orders",
                "v1.0.0");

        assertEquals("FAIL", result.status());
        assertEquals("WORKFLOW_VERSION_ALREADY_USED", result.code());
    }

    private RuntimeWorkflowDefinitionEntity workflow(String status) {
        RuntimeWorkflowDefinitionEntity workflow =
                new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-orders");
        workflow.setProjectCode("orders");
        workflow.setWorkflowKind("PAGE_ASSISTANT");
        workflow.setStatus(status);
        return workflow;
    }

    private RuntimeWorkflowVersionEntity version(String status) {
        RuntimeWorkflowVersionEntity version =
                new RuntimeWorkflowVersionEntity();
        version.setId(21L);
        version.setWorkflowId("wf-orders");
        version.setVersion("v1.0.0");
        version.setStatus(status);
        return version;
    }

    private BindingView binding(String pageKey) {
        return new BindingView(
                1L,
                "wf-orders",
                7L,
                "orders",
                "PAGE",
                pageKey,
                "TARGET",
                "ACTIVE",
                LocalDateTime.now());
    }

    private RuntimeWorkflowReleaseValidationResult valid() {
        return RuntimeWorkflowReleaseValidationResult.builder().build();
    }
}
