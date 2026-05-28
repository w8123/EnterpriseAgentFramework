package com.enterprise.ai.spring.registry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EafGraphInteractionTest {

    @Test
    @SuppressWarnings("unchecked")
    void builderEmitsInteractionNodeForSdkDeclaredUserInteraction() {
        EafAgentGraph graph = EafGraph.agent("contract_review")
                .modelInstanceId("llm-1")
                .llm("planner")
                .interaction("collectFiles", "COLLECT_INPUT")
                .interactionRef("contract.compareInput")
                .interactionField("oldFileId", "file", true, "原合同")
                .interactionField("newFileId", "file", true, "新合同")
                .outputAlias("params")
                .tool("compare")
                .ref("contract.compare")
                .input("oldFileId", "params.oldFileId")
                .input("newFileId", "params.newFileId")
                .answer("done", "{{ lastOutput }}")
                .edge("planner", "collectFiles").always()
                .edge("collectFiles", "compare").always()
                .edge("compare", "done").always()
                .build();

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.graphSpec().get("nodes");
        Map<String, Object> interaction = nodes.stream()
                .filter(node -> "INTERACTION".equals(node.get("type")))
                .findFirst()
                .orElseThrow();

        assertEquals("collectFiles", interaction.get("id"));
        assertEquals("INTERACTION", interaction.get("type"));
        assertEquals("INTERACTION", ((Map<String, Object>) interaction.get("ref")).get("kind"));
        assertEquals("contract.compareInput", ((Map<String, Object>) interaction.get("ref")).get("qualifiedName"));
        Map<String, Object> config = (Map<String, Object>) interaction.get("config");
        assertEquals("COLLECT_INPUT", config.get("interactionType"));
        assertEquals("params", config.get("outputAlias"));
        List<Map<String, Object>> fields = (List<Map<String, Object>>) config.get("fields");
        assertEquals("oldFileId", fields.get(0).get("key"));
        assertEquals("file", fields.get(0).get("type"));
        assertTrue(EafGraph.supportedNodeTypes().contains("INTERACTION"));
        assertEquals("INTERACTION", EafGraphNodeType.normalize("interaction"));
    }
}
