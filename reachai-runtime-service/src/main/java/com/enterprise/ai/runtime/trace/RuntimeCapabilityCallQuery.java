package com.enterprise.ai.runtime.trace;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Read-only evidence of successful capability calls, owned and queried by Trace. */
public interface RuntimeCapabilityCallQuery {
    List<Map<String, Object>> findSuccessfulCapabilityCalls(String agentId, LocalDateTime since, int limit);
}
