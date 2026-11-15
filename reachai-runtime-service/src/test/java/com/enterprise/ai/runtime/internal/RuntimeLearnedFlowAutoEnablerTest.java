package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.eval.RuntimeProjectEvalSettingMapper;
import com.enterprise.ai.runtime.internal.RuntimeLearnedFlowService.CandidateView;
import com.enterprise.ai.runtime.internal.RuntimeLearnedFlowService.PendingView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeLearnedFlowAutoEnablerTest {

    private final RuntimeLearnedFlowService flows = mock(RuntimeLearnedFlowService.class);
    private final RuntimeLearnedFlowAutoEnabler enabler =
            new RuntimeLearnedFlowAutoEnabler(flows, mock(RuntimeProjectEvalSettingMapper.class), true);

    @Test
    void enablesOnlyRequestsRepeatedOftenEnoughAndAFewPerAssistant() {
        List<PendingView> pending = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            pending.add(pendingView("agent-1", "cap-" + index, 5));
        }
        pending.add(pendingView("agent-2", "rare", 2));
        when(flows.pending(eq("erp"), any())).thenReturn(pending);

        assertEquals(RuntimeLearnedFlowAutoEnabler.MAX_PER_AGENT, enabler.enableFor("erp"));

        verify(flows, times(RuntimeLearnedFlowAutoEnabler.MAX_PER_AGENT)).enable(eq("agent-1"), any());
        verify(flows, never()).draft(eq("agent-2"), any());
    }

    private static PendingView pendingView(String agentId, String sourceRef, int runs) {
        return new PendingView(agentId, "助手", "erp",
                new CandidateView(sourceRef, sourceRef, runs, 1000, null, null, null, false));
    }
}
