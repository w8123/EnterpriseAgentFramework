package com.enterprise.ai.agent.skill;

import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.skill.SkillMetadata;
import com.enterprise.ai.skill.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SubAgentSkillExecutorTest {

    private SubAgentSkillExecutor executor;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<com.enterprise.ai.agent.agentscope.AgentFactory> provider = mock(ObjectProvider.class);
        executor = new SubAgentSkillExecutor(provider, new ObjectMapper());
        SubAgentSkillExecutor.resetDepth();
    }

    @AfterEach
    void tearDown() {
        SubAgentSkillExecutor.resetDepth();
    }

    @Test
    void composeChildMessageIncludesJsonArgs() {
        String msg = executor.composeChildMessage(Map.of("customerId", "C001", "amount", 100));
        assertTrue(msg.contains("[Given parameters]"), "应该包含参数分隔段");
        assertTrue(msg.contains("customerId"), "应该包含参数名");
        assertTrue(msg.contains("C001"), "应该包含参数值");
    }

    @Test
    void composeChildMessageWithoutArgsEmitsEmptyObject() {
        String msg = executor.composeChildMessage(Map.of());
        assertTrue(msg.contains("{}"), "空参数应输出空对象占位");
    }

    @Test
    void buildChildContextInheritsParentTraceId() {
        ToolExecutionContext parent = ToolExecutionContext.builder()
                .traceId("trace-parent-123")
                .sessionId("sess-1")
                .userId("u1")
                .agentName("parent")
                .intentType("biz")
                .build();
        ToolExecutionContext child = executor.buildChildContext(parent, "risk_triage");
        assertEquals("trace-parent-123", child.getTraceId(), "应沿用父 traceId");
        assertEquals("sess-1", child.getSessionId());
        assertEquals("u1", child.getUserId());
        assertEquals("skill:risk_triage", child.getAgentName());
        assertEquals("biz", child.getIntentType());
    }

    @Test
    void buildChildContextAllocatesNewTraceIdWhenParentMissing() {
        ToolExecutionContext child = executor.buildChildContext(null, "some_skill");
        assertNotNull(child.getTraceId());
        assertNotEquals("", child.getTraceId());
        assertEquals("skill:some_skill", child.getAgentName());
    }

    @Test
    void executeRejectsRecursionBeyondMaxDepth() {
        // 手工把 DEPTH 拉到 MAX_DEPTH，模拟已经嵌套了 3 层
        for (int i = 0; i < SubAgentSkillExecutor.MAX_DEPTH; i++) {
            // 利用 currentDepth 不能直接 set，这里借 execute 无法跑真 factory，
            // 所以采用反射方式直接推进 DEPTH ThreadLocal
            pushDepth();
        }
        SubAgentSkill skill = newDummySkill();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> executor.execute(skill, Map.of()));
        assertTrue(ex.getMessage().contains("嵌套层级超过"));
    }

    @Test
    void currentDepthReflectsThreadLocal() {
        assertEquals(0, SubAgentSkillExecutor.currentDepth());
        pushDepth();
        assertEquals(1, SubAgentSkillExecutor.currentDepth());
    }

    @Test
    void resolveTimeoutFallsBackToDefaultWhenMetadataInvalid() {
        SubAgentSkill skill = newDummySkill();
        Duration timeout = executor.resolveTimeout(skill);
        assertEquals(Duration.ofSeconds(60), timeout);
    }

    @Test
    void resolveRetryLimitUsesMetadataValue() {
        SubAgentSpec spec = new SubAgentSpec("prompt", List.of("tool_a"), null, null, 5, false);
        SkillMetadata metadata = new SkillMetadata("1.0.0",
                com.enterprise.ai.skill.SideEffectLevel.READ_ONLY,
                com.enterprise.ai.skill.HitlPolicy.NEVER,
                3_000,
                2,
                Map.of());
        SubAgentSkill skill = new SubAgentSkill(
                "skill_retry",
                "desc",
                "desc",
                List.of(),
                metadata,
                spec,
                executor
        );
        assertEquals(Duration.ofMillis(3_000), executor.resolveTimeout(skill));
        assertEquals(2, executor.resolveRetryLimit(skill));
    }

    /** 通过反射把 DEPTH +1，避免我们在单测里真的 call child agent。 */
    private void pushDepth() {
        try {
            java.lang.reflect.Field f = SubAgentSkillExecutor.class.getDeclaredField("DEPTH");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            ThreadLocal<Integer> tl = (ThreadLocal<Integer>) f.get(null);
            tl.set(tl.get() + 1);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private SubAgentSkill newDummySkill() {
        SubAgentSpec spec = new SubAgentSpec(
                "prompt", List.of("tool_a"), null, null, 5, false);
        return new SubAgentSkill(
                "skill_x",
                "does x",
                "x's ai description",
                List.of(new ToolParameter("id", "string", "d", true)),
                SkillMetadata.defaultFor(null),
                spec,
                executor
        );
    }
}
