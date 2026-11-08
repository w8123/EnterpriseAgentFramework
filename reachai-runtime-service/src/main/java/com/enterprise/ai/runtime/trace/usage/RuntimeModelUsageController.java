package com.enterprise.ai.runtime.trace.usage;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Control 的「AI 使用情况 · 费用 / 审计导出」读取。仅 Control 经服务签名调用（InternalServiceAuthFilter 校验签名），
 * 浏览器只走 Control 的 /api/mcp/usage/**。
 */
@RestController
public class RuntimeModelUsageController {

    private final RuntimeModelUsageRecorder recorder;

    public RuntimeModelUsageController(RuntimeModelUsageRecorder recorder) {
        this.recorder = recorder;
    }

    @GetMapping("/internal/runtime/usage/model-tokens")
    public ResponseEntity<List<RuntimeModelUsageRow>> modelTokens(
            HttpServletRequest request,
            @RequestParam(required = false) String projectCode,
            @RequestParam(defaultValue = "30") int days) {
        if (!fromControl(request)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(recorder.usage(projectCode, days));
    }

    @GetMapping("/internal/runtime/usage/audit-runs")
    public ResponseEntity<List<RuntimeAuditRunRow>> auditRuns(
            HttpServletRequest request,
            @RequestParam(required = false) String projectCode,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "10000") int limit) {
        if (!fromControl(request)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(recorder.auditRuns(projectCode, days, limit));
    }

    private static boolean fromControl(HttpServletRequest request) {
        Object raw = request.getAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR);
        return raw instanceof VerifiedInternalServiceAuth auth
                && InternalServiceAuthHeaders.CALLER_CONTROL.equals(auth.caller());
    }
}
