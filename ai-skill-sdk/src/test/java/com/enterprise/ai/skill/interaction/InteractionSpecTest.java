package com.enterprise.ai.skill.interaction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InteractionSpecTest {

    @Test
    @SuppressWarnings("unchecked")
    void presentOutputBuilderExposesDisplayContract() {
        Map<String, Object> spec = InteractionSpec.presentOutput()
                .title("班组列表")
                .component("table")
                .dataExpression("bzsdk_page_result.items")
                .renderSchema(Map.of("columns", List.of(Map.of("key", "teamName", "label", "班组"))))
                .dataSource("binding", Map.of("sourceKind", "TOOL", "ref", "bzsdk_page"))
                .rendererKey("enterprise.team.table")
                .build();

        assertEquals(InteractionType.PRESENT_OUTPUT.name(), spec.get("interactionType"));
        assertEquals("TABLE", spec.get("component"));
        assertEquals("bzsdk_page_result.items", spec.get("dataExpression"));
        assertEquals("enterprise.team.table", ((Map<String, Object>) spec.get("renderSchema")).get("rendererKey"));
        assertEquals("bzsdk_page", ((Map<String, Object>) ((Map<String, Object>) spec.get("dataSources")).get("binding")).get("ref"));
    }
}
