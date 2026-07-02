package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.trace.RuntimeToolCallLogEntity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeToolCallLogInternalControllerTest {

    @Test
    void listsToolCallsByToolNameForCapabilityMetrics() {
        RuntimeToolCallLogMapper mapper = mock(RuntimeToolCallLogMapper.class);
        RuntimeToolCallLogEntity row = new RuntimeToolCallLogEntity();
        row.setId(7L);
        row.setTraceId("trace-1");
        row.setToolName("order_form");
        row.setSuccess(true);
        row.setElapsedMs(25);
        row.setTokenCost(9);
        row.setCreateTime(LocalDateTime.of(2026, 7, 1, 10, 0));
        when(mapper.selectList(any())).thenReturn(List.of(row));

        RuntimeToolCallLogInternalController controller = new RuntimeToolCallLogInternalController(mapper);

        ResponseEntity<List<RuntimeToolCallLogInternalController.ToolCallLogRecord>> response =
                controller.listByTool("order_form", 7);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<RuntimeToolCallLogInternalController.ToolCallLogRecord> body = response.getBody();
        assertNotNull(body);
        assertEquals(1, body.size());
        assertEquals("trace-1", body.get(0).traceId());
        assertEquals("order_form", body.get(0).toolName());
        assertEquals(true, body.get(0).success());
    }
}
