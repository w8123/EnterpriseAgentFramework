package com.enterprise.ai.runtime.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class RuntimeProjectEvalServiceTest {

    private final RuntimeProjectEvalService service = new RuntimeProjectEvalService(
            mock(RuntimeProjectEvalSettingMapper.class), mock(RuntimeEvalDatasetService.class),
            mock(RuntimeEvalExperimentService.class), mock(RuntimeEvalEvaluatorSuiteService.class),
            mock(RuntimeEvalExperimentMapper.class), mock(RuntimeEvalExperimentItemMapper.class),
            mock(RuntimeEvalDatasetItemMapper.class), mock(RuntimeEvalScoreMapper.class),
            mock(com.enterprise.ai.runtime.agent.RuntimeAgentIdentityQuery.class),
            mock(com.enterprise.ai.runtime.agent.RuntimeAgentConfigService.class),
            mock(com.enterprise.ai.runtime.client.control.RuntimeAssistantCapabilityClient.class),
            mock(com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.class),
            new ObjectMapper(), false);

    @Test
    void keepsOneQuestionPerRealReadOnlyCapability() {
        List<Map<String, Object>> tools = List.of(Map.of("name", "erpSaleOrderPage"), Map.of("name", "erpStockPage"));
        String reply = "好的：\n```json\n{\"questions\":["
                + "{\"capability\":\"erpSaleOrderPage\",\"question\":\"最近有哪些销售订单\"},"
                + "{\"capability\":\"erpSaleOrderPage\",\"question\":\"本月销售订单有多少\"},"
                + "{\"capability\":\"erpCustomerCreate\",\"question\":\"帮我新建客户张三\"},"
                + "{\"capability\":\"erpStockPage\",\"question\":\"库\"},"
                + "{\"capability\":\"erpStockPage\",\"question\":\"库存低于10的商品有哪些\"}]}\n```";

        List<RuntimeProjectEvalService.Question> questions = service.parseQuestions(reply, tools, 10);

        assertEquals(List.of(
                new RuntimeProjectEvalService.Question("erpSaleOrderPage", "最近有哪些销售订单"),
                new RuntimeProjectEvalService.Question("erpStockPage", "库存低于10的商品有哪些")), questions);
        assertEquals(1, service.parseQuestions(reply, tools, 1).size());
        assertEquals(List.of(), service.parseQuestions("not json", tools, 10));
    }

    @Test
    void keepsOnlyExpectedFragmentsThatAreReallyInTheSample() {
        List<Map<String, Object>> tools = List.of(Map.of("name", "orderPage"), Map.of("name", "goodsPage"));
        String reply = "{\"questions\":["
                + "{\"capability\":\"orderPage\",\"question\":\"订单 SO-1001 是谁下的\",\"expect\":[\"SO-1001\",\"王小明\",\"编造的名字\",\"王\"]},"
                + "{\"capability\":\"goodsPage\",\"question\":\"有哪些商品\",\"expect\":[\"苹果\"]}]}";
        Map<String, String> samples = Map.of("orderPage", "{\"list\":[{\"no\":\"SO-1001\",\"buyer\":\"王小明\"}]}");

        List<RuntimeProjectEvalService.Question> questions = service.parseQuestions(reply, tools, 10, samples);

        assertEquals(List.of("SO-1001", "王小明"), questions.get(0).expect());
        assertEquals(List.of(), questions.get(1).expect());
    }

    @Test
    void samplesOnlyCapabilitiesThatNeedNoRecordFromAPerson() {
        assertEquals(Map.of("pageNo", 1, "pageSize", 10), RuntimeProjectEvalService.sampleArguments(Map.of(
                "properties", Map.of("pageNo", Map.of(), "pageSize", Map.of(), "keyword", Map.of()),
                "required", List.of("pageNo"))));
        assertNull(RuntimeProjectEvalService.sampleArguments(Map.of(
                "properties", Map.of("orderId", Map.of()), "required", List.of("orderId"))));
        assertEquals(Map.of(), RuntimeProjectEvalService.sampleArguments(null));
    }

    @Test
    void explainsFailedChecksInPlainChinese() {
        assertEquals("没有查业务数据就回答了", RuntimeProjectEvalService.reason("Failed assertions: usedBusinessData"));
        assertEquals("太慢（超过 60 秒）", RuntimeProjectEvalService.reason("Latency exceeded 60000 ms"));
        assertEquals("调用业务能力失败了", RuntimeProjectEvalService.reason("Failed assertions: capabilityLastCallFailed"));
        assertNull(RuntimeProjectEvalService.reason("All deterministic assertions passed"));
    }

    @Test
    void anApologyForNotGettingTheDataCountsAsAFailedAnswer() {
        for (String answer : List.of("抱歉，暂时无法查询订单", "凭据已过期，请稍后再试", "查询失败了")) {
            assertTrue(RuntimeProjectEvalService.FAILURE_PHRASES.stream().anyMatch(answer::contains), answer);
        }
    }
}
