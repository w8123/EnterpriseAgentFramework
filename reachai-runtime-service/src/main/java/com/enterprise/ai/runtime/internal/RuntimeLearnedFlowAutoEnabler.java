package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.eval.RuntimeProjectEvalSettingLookup;
import com.enterprise.ai.runtime.internal.RuntimeLearnedFlowService.FlowRequest;
import com.enterprise.ai.runtime.internal.RuntimeLearnedFlowService.PendingView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * For a business system whose admin turned on 「重复的只读请求自动变成直达流程」: a read-only request
 * the assistant has solved by reasoning often enough becomes a published flow attached to that
 * assistant without waiting for someone to click. Only read-only, single-capability requests are ever
 * candidates (see {@link RuntimeLearnedFlowService#candidates}); writes always stay with people.
 */
@Slf4j
@Component
public class RuntimeLearnedFlowAutoEnabler {

    static final int MIN_RUNS = 3;
    static final int MAX_PER_AGENT = 3;

    private final RuntimeLearnedFlowService flows;
    private final RuntimeProjectEvalSettingLookup settings;
    private final boolean enabled;

    public RuntimeLearnedFlowAutoEnabler(RuntimeLearnedFlowService flows, RuntimeProjectEvalSettingLookup settings,
                                         @Value("${reachai.learned-flow.auto-enable:true}") boolean enabled) {
        this.flows = flows;
        this.settings = settings;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${reachai.learned-flow.auto-enable-cron:0 37 * * * *}")
    public void run() {
        if (!enabled) return;
        List<String> projects;
        try {
            projects = settings.autoEnableProjects();
        } catch (RuntimeException unavailable) {
            log.debug("learned flow auto-enable skipped: {}", unavailable.getMessage());
            return;
        }
        for (String project : projects) {
            enableFor(project);
        }
    }

    int enableFor(String projectCode) {
        Map<String, Integer> perAgent = new HashMap<>();
        int enabledCount = 0;
        for (PendingView pending : flows.pending(projectCode, 30)) {
            if (pending.candidate().runCount() < MIN_RUNS) continue;
            if (perAgent.merge(pending.agentId(), 1, Integer::sum) > MAX_PER_AGENT) continue;
            FlowRequest request = new FlowRequest(pending.candidate().sourceRef(), null, "ReachAI 自动启用");
            try {
                flows.draft(pending.agentId(), request);
                flows.enable(pending.agentId(), request);
                enabledCount++;
                log.info("auto-enabled read-only flow {} for agent {} ({})", pending.candidate().sourceRef(),
                        pending.agentId(), projectCode);
            } catch (RuntimeException failure) {
                log.warn("auto-enable of {} for agent {} failed: {}", pending.candidate().sourceRef(),
                        pending.agentId(), failure.getMessage());
            }
        }
        return enabledCount;
    }
}
