package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeResolvedWorkflowTarget;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowPublishedVersionView;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrustedPageActionWorkflowGuardTest {
    private static final String GRAPH = """
            {"env":{"reachaiTrustedPageDraft":true},"entryNodeId":"action","inputSchema":{"type":"object","properties":{"nickname":{"type":"string"}},"required":["nickname"],"additionalProperties":false},"nodes":[
              {"id":"action","type":"PAGE_ACTION","config":{"projectCode":"mall","pageKey":"profile","actionKey":"fill","route":"/personal","inputMapping":{"nickname":"params.nickname"}}},
              {"id":"answer","type":"ANSWER","config":{"template":"已填写"}}
            ],"edges":[{"from":"action","to":"answer"}]}
            """;
    private final TrustedPageActionWorkflowGuard guard = new TrustedPageActionWorkflowGuard();

    @Test
    void pinsPublishedGraphAndRequiresFreshMatchingDeclaration() {
        var declaration = declaration(1, "a".repeat(64), true, "v1");
        var target = target(GRAPH, "ACTIVE");
        var pin = guard.pin(target, declaration).orElseThrow();
        assertEquals(new TrustedPageActionWorkflowGuard.Candidate("mall", "profile", "fill"), guard.candidate(target).orElseThrow());
        assertTrue(guard.argumentsMatch(pin, Map.of("nickname", "小黑")));
        assertFalse(guard.argumentsMatch(pin, Map.of()));
        assertFalse(guard.argumentsMatch(pin, Map.of("nickname", 123)));
        assertFalse(guard.argumentsMatch(pin, Map.of("nickname", "小黑", "extra", "hidden")));
        assertFalse(guard.argumentsMatch(pin, java.util.Collections.singletonMap("nickname", null)));
        assertTrue(guard.matches(pin, target, declaration));
        assertFalse(guard.matches(pin, target, declaration(2, "a".repeat(64), true, "v1")));
        assertFalse(guard.matches(pin, target, declaration(1, "b".repeat(64), true, "v1")));
        assertFalse(guard.matches(pin, target, declaration(1, "a".repeat(64), false, "v1")));
        assertFalse(guard.matches(pin, target, declaration(1, "a".repeat(64), true, "v2")));
        assertFalse(guard.matches(pin, target(GRAPH + " ", "ACTIVE"), declaration));
        assertFalse(guard.matches(pin, target(GRAPH, "DRAFT"), declaration));
        assertTrue(guard.pin(target(GRAPH.replace("\"reachaiTrustedPageDraft\":true", "\"reachaiTrustedPageDraft\":false"), "ACTIVE"), declaration).isEmpty());
        assertTrue(guard.pin(target(GRAPH.replace("\"reachaiTrustedPageDraft\":true", "\"reachaiTrustedPageDraft\":\"true\""), "ACTIVE"), declaration).isEmpty());
    }

    @Test
    void refusesOtherNodeTypesMultipleActionsAndCyclicTopology() {
        var d = declaration(1, "a".repeat(64), true, "v1");
        for (String type : new String[]{"TOOL", "HTTP", "WORKFLOW", "LLM", "UNKNOWN", "page_action"}) {
            assertTrue(guard.pin(target(GRAPH.replace("\"ANSWER\"", "\"" + type + "\""), "ACTIVE"), d).isEmpty(), type);
        }
        assertTrue(guard.pin(target(GRAPH.replace("\"ANSWER\"", "\"PAGE_ACTION\""), "ACTIVE"), d).isEmpty());
        assertTrue(guard.pin(target(GRAPH.replace("\"to\":\"answer\"", "\"to\":\"action\""), "ACTIVE"), d).isEmpty());
        assertTrue(guard.pin(target(GRAPH.replace("\"entryNodeId\":\"action\"", "\"entryNodeId\":\"answer\""), "ACTIVE"), d).isEmpty());
    }

    @Test
    void rejectsDynamicTargetSchemaAndAmbiguousJson() {
        var d = declaration(1, "a".repeat(64), true, "v1");
        for (String graph : new String[]{
                GRAPH.replace("\"pageKey\":\"profile\"", "\"pageKey\":\"${input.page}\""),
                GRAPH.replace("\"actionKey\":\"fill\"", "\"actionKey\":\"{{action}}\""),
                GRAPH.replace("\"route\":\"/personal\"", "\"route\":\"/other\""),
                GRAPH.replace("\"route\":\"/personal\"", "\"route\":\"${input.route}\""),
                GRAPH.replace("\"params.nickname\"", "\"params.other\""),
                GRAPH.replace("nickname", "user.name"),
                GRAPH.replace("nickname", "sessionId"),
                GRAPH.replace("\"params.nickname\"", "\"{{params.nickname}}\""),
                GRAPH.replace("\"inputMapping\":{\"nickname\":\"params.nickname\"}", "\"args\":{\"nickname\":\"secret\"}"),
                GRAPH.replace("\"inputMapping\":{\"nickname\":\"params.nickname\"}", "\"inputMapping\":{\"nickname\":\"params.nickname\"},\"args\":{\"extra\":\"hidden\"}"),
                GRAPH.replace("\"type\":\"string\"", "\"type\":\"string\",\"default\":\"hidden\""),
                GRAPH.replace("\"required\":[\"nickname\"]", "\"required\":[]"),
                GRAPH.replace("\"entryNodeId\":\"action\"", "\"entryNodeId\":\"action\",\"inputSchema\":{\"$ref\":\"https://example.com/schema\"}"),
                GRAPH.replace("\"pageKey\":\"profile\"", "\"pageKey\":\"profile\",\"pageKey\":\"other\""),
                GRAPH.replace("\"pageKey\":\"profile\"", "\"pageKey\":\"profile\",\"unknownOverride\":\"x\"")}) {
            assertTrue(guard.pin(target(graph, "ACTIVE"), d).isEmpty(), graph);
        }
    }

    @Test
    void refusesNonDraftEffectAndUntrustedScope() {
        var d = declaration(1, "a".repeat(64), true, "v1");
        var invalidEffect = new TrustedPageActionWorkflowGuard.TrustedActionDeclaration(1L, 1, 3L, 4L,
                "mall", "profile", "fill", "WRITE", d.catalogHash(), "v1", "修改资料", "修改资料", "填写", "/personal", d.inputSchemaJson(), d.fieldLabels(), true);
        assertTrue(guard.pin(target(GRAPH, "ACTIVE"), invalidEffect).isEmpty());
        assertTrue(guard.pin(target(GRAPH.replace("\"projectCode\":\"mall\"", "\"projectCode\":\"other\""), "ACTIVE"), d).isEmpty());
        assertTrue(guard.pin(target(GRAPH, "ACTIVE"), declaration(1, "invalid", true, "v1")).isEmpty());
        assertTrue(guard.pin(target(GRAPH, "ACTIVE"), null).isEmpty());
    }

    @Test
    void rejectsReservedAndDottedFieldsEvenWhenBothSchemasAgree() {
        var original = declaration(1, "a".repeat(64), true, "v1");
        for (String field : new String[]{"sessionId", "params", "user.name", "constructor"}) {
            var d = new TrustedPageActionWorkflowGuard.TrustedActionDeclaration(original.approvalId(), original.revision(),
                    original.pageId(), original.actionId(), original.projectCode(), original.pageKey(), original.actionKey(),
                    original.effect(), original.catalogHash(), original.implementationVersion(), original.targetAlias(),
                    original.actionTitle(), original.actionDescription(), original.routePattern(),
                    original.inputSchemaJson().replace("nickname", field), Map.of(field, "昵称"), true);
            assertTrue(guard.pin(target(GRAPH.replace("nickname", field), "ACTIVE"), d).isEmpty(), field);
        }
    }

    @Test
    void acceptsOnlyExplicitNonemptyOptionalFieldsWithoutSupplyingDefaults() {
        var original = declaration(1, "a".repeat(64), true, "v1");
        String schema = "{\"type\":\"object\",\"properties\":{\"nickname\":{\"type\":\"string\"},\"signature\":{\"type\":\"string\"}},\"required\":[],\"additionalProperties\":false}";
        String graph = GRAPH.replace(original.inputSchemaJson(), schema)
                .replace("\"nickname\":\"params.nickname\"", "\"nickname\":\"params.nickname\",\"signature\":\"params.signature\"");
        var d = new TrustedPageActionWorkflowGuard.TrustedActionDeclaration(original.approvalId(), original.revision(),
                original.pageId(), original.actionId(), original.projectCode(), original.pageKey(), original.actionKey(),
                original.effect(), original.catalogHash(), original.implementationVersion(), original.targetAlias(),
                original.actionTitle(), original.actionDescription(), original.routePattern(), schema, Map.of("nickname", "昵称", "signature", "签名"), true);
        var pin = guard.pin(target(graph, "ACTIVE"), d).orElseThrow();
        assertTrue(guard.argumentsMatch(pin, Map.of("nickname", "小黑")));
        assertTrue(guard.argumentsMatch(pin, Map.of("signature", "你好")));
        assertFalse(guard.argumentsMatch(pin, Map.of()));
        assertFalse(guard.argumentsMatch(pin, Map.of("nickname", "  ")));
        assertFalse(guard.argumentsMatch(pin, java.util.Collections.singletonMap("nickname", null)));
        assertFalse(guard.argumentsMatch(pin, Map.of("nickname", "小黑", "other", "unexpected")));
        String implicitOptional = schema.replace(",\"required\":[]", "").replace(",\"additionalProperties\":false", "");
        String implicitGraph = graph.replace(schema, implicitOptional);
        var implicitDeclaration = new TrustedPageActionWorkflowGuard.TrustedActionDeclaration(original.approvalId(), original.revision(),
                original.pageId(), original.actionId(), original.projectCode(), original.pageKey(), original.actionKey(),
                original.effect(), original.catalogHash(), original.implementationVersion(), original.targetAlias(),
                original.actionTitle(), original.actionDescription(), original.routePattern(), implicitOptional, Map.of("nickname", "昵称", "signature", "签名"), true);
        var implicitPin = guard.pin(target(implicitGraph, "ACTIVE"), implicitDeclaration).orElseThrow();
        assertTrue(guard.argumentsMatch(implicitPin, Map.of("signature", "你好")));
        assertFalse(guard.argumentsMatch(implicitPin, Map.of("signature", "你好", "other", "unexpected")));
    }

    @Test
    void enforcesReviewedLabelsAndStringLengthByUnicodeCodePoint() {
        var original = declaration(1, "a".repeat(64), true, "v1");
        String schema = "{\"type\":\"object\",\"properties\":{\"nickname\":{\"type\":\"string\",\"minLength\":2,\"maxLength\":3}},\"minProperties\":1,\"additionalProperties\":false}";
        String graph = GRAPH.replace(original.inputSchemaJson(), schema);
        var approved = new TrustedPageActionWorkflowGuard.TrustedActionDeclaration(original.approvalId(), original.revision(),
                original.pageId(), original.actionId(), original.projectCode(), original.pageKey(), original.actionKey(),
                original.effect(), original.catalogHash(), original.implementationVersion(), original.targetAlias(),
                original.actionTitle(), null, original.routePattern(), schema, Map.of("nickname", "昵称"), true);
        var pin = guard.pin(target(graph, "ACTIVE"), approved).orElseThrow();
        assertTrue(guard.argumentsMatch(pin, Map.of("nickname", "😀黑")));
        assertFalse(guard.argumentsMatch(pin, Map.of("nickname", "😀")));
        assertFalse(guard.argumentsMatch(pin, Map.of("nickname", "一二三四")));
        var missingLabel = new TrustedPageActionWorkflowGuard.TrustedActionDeclaration(original.approvalId(), original.revision(),
                original.pageId(), original.actionId(), original.projectCode(), original.pageKey(), original.actionKey(),
                original.effect(), original.catalogHash(), original.implementationVersion(), original.targetAlias(),
                original.actionTitle(), null, original.routePattern(), schema, Map.of(), true);
        assertTrue(guard.pin(target(graph, "ACTIVE"), missingLabel).isEmpty());
        assertTrue(guard.pin(target(graph.replace("\"minLength\":2", "\"minLength\":-1"), "ACTIVE"), approved).isEmpty());
    }

    @Test
    void acceptsOnlyExactInputContractBeforeTheAction() {
        var d = declaration(1, "a".repeat(64), true, "v1");
        String graph = GRAPH.replace("\"entryNodeId\":\"action\"", "\"entryNodeId\":\"input\"")
                .replace("\"nodes\":[", "\"nodes\":[{\"id\":\"input\",\"type\":\"USER_INPUT\",\"config\":{\"outputAlias\":\"params\",\"fields\":[{\"name\":\"nickname\",\"type\":\"string\",\"required\":true,\"source\":\"input.nickname\"}]}},")
                .replace("\"edges\":[", "\"edges\":[{\"from\":\"input\",\"to\":\"action\"},");
        assertTrue(guard.pin(target(graph, "ACTIVE"), d).isPresent());
        assertTrue(guard.pin(target(graph.replace("input.nickname", "input.message"), "ACTIVE"), d).isEmpty());
        assertTrue(guard.pin(target(graph.replace("\"required\":true", "\"required\":false"), "ACTIVE"), d).isEmpty());
        assertTrue(guard.pin(target(graph.replace("\"source\":\"input.nickname\"", "\"source\":\"input.nickname\",\"defaultValue\":\"hidden\""), "ACTIVE"), d).isEmpty());
        assertTrue(guard.pin(target(graph.replace("\"from\":\"input\",\"to\":\"action\"", "\"from\":\"input\",\"to\":\"answer\""), "ACTIVE"), d).isEmpty());
    }

    private RuntimeResolvedWorkflowTarget target(String graph, String status) {
        return new RuntimeResolvedWorkflowTarget(null,
                RuntimeWorkflowExecutionView.builder().id("wf").projectCode("mall").build(),
                RuntimeWorkflowPublishedVersionView.builder().id(2L).workflowId("wf").version("1")
                        .status(status).graphSpecSnapshotJson(graph).build());
    }

    private TrustedPageActionWorkflowGuard.TrustedActionDeclaration declaration(long revision, String hash,
                                                                                 boolean active, String impl) {
        return new TrustedPageActionWorkflowGuard.TrustedActionDeclaration(1L, revision, 3L, 4L, "mall", "profile",
                "fill", "PAGE_LOCAL_DRAFT", hash, impl, "修改资料", "修改资料", "填写", "/personal",
                "{\"type\":\"object\",\"properties\":{\"nickname\":{\"type\":\"string\"}},\"required\":[\"nickname\"],\"additionalProperties\":false}", Map.of("nickname", "昵称"), active);
    }
}
