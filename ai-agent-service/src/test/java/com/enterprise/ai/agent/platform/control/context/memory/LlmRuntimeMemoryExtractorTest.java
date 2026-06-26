package com.enterprise.ai.agent.platform.control.context.memory;

import com.enterprise.ai.agent.llm.LlmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmRuntimeMemoryExtractorTest {

    private LlmService llmService;
    private LlmRuntimeMemoryExtractor extractor;

    @BeforeEach
    void setUp() {
        llmService = mock(LlmService.class);
        extractor = new LlmRuntimeMemoryExtractor(llmService, new ObjectMapper());
    }

    @Test
    void parsesFencedJsonCandidatesAndFiltersLowConfidence() {
        when(llmService.chat(any(), any(), eq("model-memory"))).thenReturn("""
                ```json
                {
                  "candidates": [
                    {
                      "candidateType": "PREFERENCE",
                      "title": "界面偏好",
                      "content": "用户偏好使用深色模式",
                      "summary": "偏好深色模式",
                      "reason": "长期偏好",
                      "confidence": 0.91
                    },
                    {
                      "candidateType": "NOTE",
                      "title": "临时查询",
                      "content": "用户今天问过订单",
                      "summary": "临时信息",
                      "reason": "只是本轮上下文",
                      "confidence": 0.40
                    }
                  ]
                }
                ```
                """);

        List<RuntimeMemoryExtraction> candidates = extractor.extract(RuntimeMemoryExtractionRequest.builder()
                .modelInstanceId("model-memory")
                .userMessage("以后界面请默认深色模式")
                .assistantReply("好的")
                .build());

        assertEquals(1, candidates.size());
        assertEquals(ContextMemoryCandidateType.PREFERENCE, candidates.get(0).getCandidateType());
        assertEquals("用户偏好使用深色模式", candidates.get(0).getContent());
        assertEquals(new BigDecimal("0.9100"), candidates.get(0).getConfidence());
    }

    @Test
    void returnsEmptyWhenModelSaysNoCandidates() {
        when(llmService.chat(any(), any(), eq("model-memory")))
                .thenReturn("{\"candidates\":[]}");

        List<RuntimeMemoryExtraction> candidates = extractor.extract(RuntimeMemoryExtractionRequest.builder()
                .modelInstanceId("model-memory")
                .userMessage("查一下订单状态")
                .assistantReply("订单处理中")
                .build());

        assertTrue(candidates.isEmpty());
    }
}
