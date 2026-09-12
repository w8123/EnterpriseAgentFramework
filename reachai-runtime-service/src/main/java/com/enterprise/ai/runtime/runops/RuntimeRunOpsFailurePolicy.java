package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsGuardDecisionView;
import org.springframework.util.StringUtils;

import java.util.Locale;

/** RunOps 详情与诊断共用失败判定；根运行生命周期和 Trace Span 状态各自解释。 */
final class RuntimeRunOpsFailurePolicy {

    private RuntimeRunOpsFailurePolicy() {
    }

    static boolean isRunFailureStatus(String status) {
        RuntimeRunStatus normalized = RuntimeRunStatus.parse(status);
        return normalized == RuntimeRunStatus.FAILED
                || normalized == RuntimeRunStatus.TIMED_OUT
                || normalized == RuntimeRunStatus.CANCELLED;
    }

    static boolean isSpanFailureStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return false;
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "FAILED", "ERROR", "CANCELLED", "TIMED_OUT", "TIMEOUT" -> true;
            case "RUNNING", "SUCCESS", "SUSPENDED", "WAITING_USER", "WAITING_APPROVAL", "BUSINESS_TERMINAL" -> false;
            default -> throw new IllegalArgumentException("Unsupported Trace span status: " + status);
        };
    }

    static boolean isDenied(RuntimeRunOpsGuardDecisionView guard) {
        return guard != null && "DENY".equalsIgnoreCase(guard.decision());
    }

    static boolean isToolFailure(RuntimeRunOpsViews.RuntimeRunOpsToolCallView tool) {
        if (!StringUtils.hasText(tool.status())) return !tool.success();
        if ("UNKNOWN".equals(tool.status())) return false;
        return isSpanFailureStatus(tool.status());
    }

}
