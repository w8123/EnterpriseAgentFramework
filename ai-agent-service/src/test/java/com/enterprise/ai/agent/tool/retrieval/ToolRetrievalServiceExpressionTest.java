package com.enterprise.ai.agent.tool.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link ToolRetrievalService#buildExpression(RetrievalScope)} 能根据 scope
 * 组合出正确的 Milvus boolean 表达式 —— 特别是 Phase 2.0 新增的 {@code kind} 过滤。
 * <p>
 * 这里不需要真的 Milvus / Embedding 客户端，直接用 null 也能调到 buildExpression，
 * 因为它只读字段常量。
 */
class ToolRetrievalServiceExpressionTest {

    private final ToolRetrievalService service = new ToolRetrievalService(null, null, null, null, null, null);

    @Test
    void nullScopeReturnsNull() {
        assertNull(service.buildExpression(null));
    }

    @Test
    void enabledAndVisibleFlagsAreIncluded() {
        RetrievalScope scope = RetrievalScope.allEnabled();
        String expr = service.buildExpression(scope);
        assertTrue(expr.contains("enabled == true"), "应包含 enabled == true，实际: " + expr);
        assertTrue(expr.contains("agent_visible == true"), "应包含 agent_visible == true，实际: " + expr);
    }

    @Test
    void whitelistAndScopeIdsJoinedWithAnd() {
        RetrievalScope scope = new RetrievalScope(
                List.of(1L, 2L), List.of(9L), List.of(100L, 101L), true, true);
        String expr = service.buildExpression(scope);
        assertTrue(expr.contains("tool_id in [100,101]"), expr);
        assertTrue(expr.contains("project_id in [1,2]"), expr);
        assertTrue(expr.contains("module_id in [9]"), expr);
        assertTrue(expr.contains("&&"), "多条件应用 && 连接，实际: " + expr);
    }

    @Test
    void kindFilterEmitsQuotedInList() {
        RetrievalScope scope = new RetrievalScope(
                null, null, null, true, true, Set.of("SKILL"));
        String expr = service.buildExpression(scope);
        assertTrue(expr.contains("kind in [\"SKILL\"]"), "实际: " + expr);
    }

    @Test
    void onlyToolsScopeFiltersTool() {
        String expr = service.buildExpression(RetrievalScope.onlyTools());
        assertTrue(expr.contains("kind in [\"TOOL\"]"), expr);
    }

    @Test
    void emptyKindSetIsIgnored() {
        RetrievalScope scope = new RetrievalScope(
                null, null, null, false, false, Set.of());
        assertNull(service.buildExpression(scope), "空 scope 应返回 null");
    }

    @Test
    void backwardCompatibleConstructorWorks() {
        RetrievalScope scope = new RetrievalScope(null, null, null, true, true);
        assertEquals(null, scope.kinds());
    }
}
