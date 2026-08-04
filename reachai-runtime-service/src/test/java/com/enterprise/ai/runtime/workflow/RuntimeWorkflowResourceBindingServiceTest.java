package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingInput;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowResourceBindingServiceTest {

    @Test
    void persistsOneExplicitTargetPageForAPageAssistant() {
        RuntimeWorkflowResourceBindingMapper mapper =
                mock(RuntimeWorkflowResourceBindingMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        RuntimeWorkflowResourceBindingService service =
                new RuntimeWorkflowResourceBindingService(mapper);

        service.replace(
                workflow("PAGE_ASSISTANT"),
                List.of(new BindingInput(
                        12L,
                        "orders",
                        "page",
                        "orders.detail",
                        null)));

        ArgumentCaptor<RuntimeWorkflowResourceBindingEntity> captor =
                ArgumentCaptor.forClass(RuntimeWorkflowResourceBindingEntity.class);
        verify(mapper).insert(captor.capture());
        assertEquals("PAGE", captor.getValue().getResourceType());
        assertEquals("orders.detail", captor.getValue().getResourceKey());
        assertEquals("TARGET", captor.getValue().getBindingRole());
    }

    @Test
    void rejectsMissingMultipleOrRelatedOnlyTargetPagesBeforeWriting() {
        RuntimeWorkflowResourceBindingMapper mapper =
                mock(RuntimeWorkflowResourceBindingMapper.class);
        RuntimeWorkflowResourceBindingService service =
                new RuntimeWorkflowResourceBindingService(mapper);
        RuntimeWorkflowDefinitionEntity workflow = workflow("PAGE_ASSISTANT");

        assertThrows(IllegalArgumentException.class,
                () -> service.replace(workflow, List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.replace(
                workflow,
                List.of(new BindingInput(
                        12L, "orders", "PAGE", "orders.detail", "RELATED"))));
        assertThrows(IllegalArgumentException.class, () -> service.replace(
                workflow,
                List.of(
                        new BindingInput(
                                12L, "orders", "PAGE", "orders.detail", "TARGET"),
                        new BindingInput(
                                12L, "orders", "PAGE", "orders.list", "TARGET"))));

        verify(mapper, never()).delete(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void rejectsDuplicateResourceKeysAndUnknownBindingRoles() {
        RuntimeWorkflowResourceBindingMapper mapper =
                mock(RuntimeWorkflowResourceBindingMapper.class);
        RuntimeWorkflowResourceBindingService service =
                new RuntimeWorkflowResourceBindingService(mapper);
        RuntimeWorkflowDefinitionEntity workflow = workflow("GENERAL");

        assertThrows(IllegalArgumentException.class, () -> service.replace(
                workflow,
                List.of(
                        new BindingInput(
                                12L, "orders", "PAGE", "orders.detail", "TARGET"),
                        new BindingInput(
                                12L, "orders", "PAGE", "orders.detail", "RELATED"))));
        assertThrows(IllegalArgumentException.class, () -> service.replace(
                workflow,
                List.of(new BindingInput(
                        12L, "orders", "PAGE", "orders.detail", "OWNER"))));

        verify(mapper, never()).delete(any());
        verify(mapper, never()).insert(any());
    }

    private RuntimeWorkflowDefinitionEntity workflow(String kind) {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-orders");
        workflow.setProjectId(12L);
        workflow.setProjectCode("orders");
        workflow.setWorkflowKind(kind);
        return workflow;
    }
}
