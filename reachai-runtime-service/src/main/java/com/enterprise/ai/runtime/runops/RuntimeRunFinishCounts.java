package com.enterprise.ai.runtime.runops;

import lombok.Data;

/** Aggregated RunOps finish counters from a single SQL round-trip. */
@Data
public class RuntimeRunFinishCounts {

    private Long toolCallCount;
    private Long guardDenyCount;
    private Long approvalCount;

    public static RuntimeRunFinishCounts zeros() {
        RuntimeRunFinishCounts counts = new RuntimeRunFinishCounts();
        counts.toolCallCount = 0L;
        counts.guardDenyCount = 0L;
        counts.approvalCount = 0L;
        return counts;
    }

    public int toolCallCountInt() {
        return toInt(toolCallCount);
    }

    public int guardDenyCountInt() {
        return toInt(guardDenyCount);
    }

    public int approvalCountInt() {
        return toInt(approvalCount);
    }

    private static int toInt(Long value) {
        if (value == null || value <= 0L) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, value);
    }
}
