package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeTraceCenterControllerTest {

    @Test
    void listsGuardDecisionsFromRuntimeOwnedGuardLog() {
        RuntimeGuardDecisionLogMapper mapper = mock(RuntimeGuardDecisionLogMapper.class);
        RuntimeTraceCenterController controller = new RuntimeTraceCenterController(mapper);
        RuntimeGuardDecisionLogEntity decision = new RuntimeGuardDecisionLogEntity();
        decision.setId(1L);
        decision.setTraceId("trace-1");
        decision.setDecisionType("TOOL_ACL");
        decision.setTargetKind("TOOL");
        decision.setTargetName("orders.search");
        decision.setDecision("DENY");
        decision.setReason("not allowed");
        decision.setMetadataJson("{\"policy\":\"strict\"}");
        decision.setCreatedAt(LocalDateTime.parse("2026-06-30T12:00:00"));
        when(mapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeGuardDecisionLogEntity>>any()))
                .thenReturn(List.of(decision));

        ResponseEntity<List<RuntimeGuardDecisionLogEntity>> response = controller.listGuardDecisions(
                "trace-1",
                "TOOL_ACL",
                "TOOL",
                "orders.search",
                "DENY",
                null,
                null,
                50);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("orders.search", response.getBody().get(0).getTargetName());
        verify(mapper).selectList(org.mockito.ArgumentMatchers.<Wrapper<RuntimeGuardDecisionLogEntity>>any());
    }
}
