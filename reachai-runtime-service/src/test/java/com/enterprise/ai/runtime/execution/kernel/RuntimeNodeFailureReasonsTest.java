package com.enterprise.ai.runtime.execution.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeNodeFailureReasonsTest {

    @Test
    void namesTheMissingGraphSpecField() {
        String reason = RuntimeNodeFailureReasons.safeReason("RUNTIME_GRAPH_PARAMETER_REQUIRED",
                "PARAMETER_EXTRACT required field is missing: productId");

        assertTrue(reason.contains("productId"));
    }

    @Test
    void neverEchoesModelOrUpstreamText() {
        String reason = RuntimeNodeFailureReasons.safeReason("RUNTIME_GRAPH_PARAMETER_REQUIRED",
                "model output JSON is invalid: Unexpected character ('客户手机号 13800000000')");

        assertEquals("参数提取时模型返回的 JSON 无法解析", reason);
        assertFalse(RuntimeNodeFailureReasons.safeReason("RUNTIME_GRAPH_PARAMETER_REQUIRED",
                "PARAMETER_EXTRACT required field is missing: x; secret=abc").contains("secret"));
        assertEquals("业务方法或 API 调用失败",
                RuntimeNodeFailureReasons.safeReason("RUNTIME_GRAPH_TOOL_FAILED", "upstream said token=xyz"));
    }

    @Test
    void unknownCodesHaveNoReason() {
        assertNull(RuntimeNodeFailureReasons.safeReason("SOMETHING_ELSE", "anything"));
        assertNull(RuntimeNodeFailureReasons.safeReason(null, "anything"));
    }
}
