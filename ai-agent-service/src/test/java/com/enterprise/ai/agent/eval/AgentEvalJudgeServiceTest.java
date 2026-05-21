package com.enterprise.ai.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvalJudgeServiceTest {

    @Test
    void passesWhenAnswerContainsRequiredTextAndJsonPathMatches() {
        AgentEvalJudgeService service = new AgentEvalJudgeService(new ObjectMapper());

        AgentEvalJudgeService.JudgeResult result = service.judge(
                "已查询到订单1001，状态为已支付",
                Map.of(
                        "answer", "已查询到订单1001，状态为已支付",
                        "finalState", Map.of("orderId", "1001")),
                """
                        {"contains":["订单1001"],"jsonPath":{"finalState.orderId":"1001"}}
                        """,
                "{}");

        assertTrue(result.passed());
        assertEquals(1.0, result.score());
        assertTrue(result.failures().isEmpty());
    }

    @Test
    void failsWhenRegexDoesNotMatch() {
        AgentEvalJudgeService service = new AgentEvalJudgeService(new ObjectMapper());

        AgentEvalJudgeService.JudgeResult result = service.judge(
                "没有找到订单",
                Map.of("answer", "没有找到订单"),
                "{\"regex\":[\"订单\\\\d+\"]}",
                "{}");

        assertFalse(result.passed());
        assertEquals(0.0, result.score());
        assertTrue(result.failures().get(0).contains("regex"));
    }

    @Test
    void noConfiguredAssertionsTreatsNonBlankAnswerAsWeakPass() {
        AgentEvalJudgeService service = new AgentEvalJudgeService(new ObjectMapper());

        AgentEvalJudgeService.JudgeResult result = service.judge(
                "可以办理退款",
                Map.of("answer", "可以办理退款"),
                "{}",
                "{}");

        assertTrue(result.passed());
        assertEquals(0.6, result.score());
    }
}
