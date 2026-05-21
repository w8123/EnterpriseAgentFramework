package com.enterprise.ai.agent.studio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowDraftGenerationServiceTest {

    @Test
    void generateReportsClearReasonWhenNoProviderSupportsRequest() {
        WorkflowDraftGenerationService service = new WorkflowDraftGenerationService(List.of());

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                service.generate(WorkflowDraftGenerationRequest.builder()
                        .requirement("生成合同审批流程")
                        .modelInstanceId("model-1")
                        .build()));

        assertTrue(error.getMessage().contains("没有可用"));
    }
}
