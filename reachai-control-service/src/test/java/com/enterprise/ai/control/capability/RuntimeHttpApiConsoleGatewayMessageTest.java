package com.enterprise.ai.control.capability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeHttpApiConsoleGatewayMessageTest {

    @Test
    void forwardsFixedRuntimeHttpApiConflictReasons() {
        assertTrue(RuntimeHttpApiConsoleGateway.userFacingRuntimeMessage(
                "HTTP_API_AUTH_MISMATCH", "项目凭据类型与连接认证方式不一致"));
        assertTrue(RuntimeHttpApiConsoleGateway.userFacingRuntimeMessage(
                "HTTP_API_NOT_ACCEPTED", "当前 API 契约或来源尚不能配置试调用"));
    }

    @Test
    void keepsGenericMessageForOtherCodesOrUnsafeText() {
        assertFalse(RuntimeHttpApiConsoleGateway.userFacingRuntimeMessage("RUNTIME_INTERNAL", "内部错误"));
        assertFalse(RuntimeHttpApiConsoleGateway.userFacingRuntimeMessage("HTTP_API_X", null));
        assertFalse(RuntimeHttpApiConsoleGateway.userFacingRuntimeMessage("HTTP_API_X", "see https://internal.host/a"));
        assertFalse(RuntimeHttpApiConsoleGateway.userFacingRuntimeMessage("HTTP_API_X", "line1\nline2"));
        assertFalse(RuntimeHttpApiConsoleGateway.userFacingRuntimeMessage("HTTP_API_X", "x".repeat(121)));
    }
}
