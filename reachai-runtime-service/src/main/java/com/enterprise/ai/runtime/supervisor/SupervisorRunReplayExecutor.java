package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.runops.RuntimeRunReplayExecutionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Map;

/** Binds RunOps replay commands to the same pinned-configuration execution used by other Agent entries. */
@Service
@RequiredArgsConstructor
public class SupervisorRunReplayExecutor implements RuntimeRunReplayExecutionPort {
    private final RuntimeAgentExecutionService executionService;

    @Override
    public Map<String, Object> executePublishedAgent(String agentId, Long configVersionId, Map<String, Object> input) {
        return executionService.executePublishedConfig(agentId, configVersionId, input, true);
    }
}
