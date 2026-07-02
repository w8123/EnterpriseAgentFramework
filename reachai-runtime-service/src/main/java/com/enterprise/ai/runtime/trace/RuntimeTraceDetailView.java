package com.enterprise.ai.runtime.trace;

import java.util.List;

public record RuntimeTraceDetailView(String traceId, List<RuntimeTraceNodeView> nodes) {
}
