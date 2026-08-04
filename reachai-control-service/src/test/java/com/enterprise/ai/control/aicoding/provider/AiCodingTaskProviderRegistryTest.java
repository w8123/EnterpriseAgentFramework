package com.enterprise.ai.control.aicoding.provider;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyResult;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskContract;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiCodingTaskProviderRegistryTest {

    @Test
    void registersKindsAndRejectsDuplicatesOrMismatchedContracts() {
        AiCodingTaskProviderRegistry registry =
                new AiCodingTaskProviderRegistry(List.of(provider(
                        "PAGE_MAP_SCAN",
                        "PAGE_MAP_SCAN")));
        assertEquals(
                "PAGE_MAP_SCAN",
                registry.require("page_map_scan").kind());
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.require("UNKNOWN_TASK"));
        assertThrows(
                IllegalStateException.class,
                () -> new AiCodingTaskProviderRegistry(List.of(
                        provider("PAGE_MAP_SCAN", "PAGE_MAP_SCAN"),
                        provider("PAGE_MAP_SCAN", "PAGE_MAP_SCAN"))));
        assertThrows(
                IllegalStateException.class,
                () -> new AiCodingTaskProviderRegistry(List.of(
                        provider("PAGE_MAP_SCAN", "CODE_IMPLEMENTATION"))));
    }

    private static AiCodingTaskKindProvider provider(
            String kind,
            String contractKind) {
        return new AiCodingTaskKindProvider() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public TaskContract contract() {
                JsonNode empty = JsonNodeFactory.instance.objectNode();
                return new TaskContract(
                        "BUSINESS_PAGE_WORKBENCH",
                        contractKind,
                        "READ_ONLY",
                        "PROJECT",
                        "reachai.test-report",
                        "v1",
                        empty,
                        empty);
            }

            @Override
            public JsonNode buildContext(TaskDescriptor task) {
                return JsonNodeFactory.instance.objectNode();
            }

            @Override
            public List<ReadinessItem> readiness(TaskDescriptor task) {
                return List.of();
            }

            @Override
            public ArtifactApplyResult applyArtifact(
                    TaskDescriptor task,
                    ArtifactEnvelope artifact) {
                return ArtifactApplyResult.complete(
                        "done",
                        JsonNodeFactory.instance.objectNode());
            }
        };
    }
}
