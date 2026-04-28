package com.enterprise.ai.agent.skill.interactive;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveFormFieldTreeTest {

    @Test
    void flattenLeaves_skipsExplicitEmptyChildren() {
        FieldSpec bodyJson = FieldSpec.builder()
                .key("body_json")
                .label("body")
                .type("text")
                .required(true)
                .source(FieldSourceSpec.builder().kind("NONE").build())
                .children(List.of())
                .build();
        List<InteractiveFormFieldTree.LeafBinding> leaves =
                InteractiveFormFieldTree.flattenLeaves(List.of(bodyJson), List.of());
        assertTrue(leaves.isEmpty());
    }

    @Test
    void mergeEmptyGroupDefaults_putsEmptyMapForBodyJson() {
        FieldSpec bodyJson = FieldSpec.builder()
                .key("body_json")
                .label("body")
                .type("text")
                .required(true)
                .source(FieldSourceSpec.builder().kind("NONE").build())
                .children(List.of())
                .build();
        Map<String, Object> nested = new LinkedHashMap<>();
        InteractiveFormFieldTree.mergeEmptyGroupDefaults(nested, List.of(bodyJson), List.of());
        assertTrue(nested.get("body_json") instanceof Map<?, ?>);
        assertTrue(((Map<?, ?>) nested.get("body_json")).isEmpty());
    }
}
