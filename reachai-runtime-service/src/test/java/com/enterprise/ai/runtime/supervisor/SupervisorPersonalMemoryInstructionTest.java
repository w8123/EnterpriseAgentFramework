package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentMemoryPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SupervisorPersonalMemoryInstructionTest {

    @Test
    void disabledWriteForbidsClaimingSavedMemory() {
        String instruction = AgentScopeSupervisorRuntimeAdapter.personalMemoryInstruction(
                new RuntimeAgentMemoryPolicy(true, 40, false, RuntimeAgentMemoryPolicy.WRITE_OFF));
        assertTrue(instruction.contains("disabled"));
        assertTrue(instruction.contains("do not claim it was saved"));
    }

    @Test
    void enabledWritePointsUserToMemoryPanel() {
        String instruction = AgentScopeSupervisorRuntimeAdapter.personalMemoryInstruction(
                new RuntimeAgentMemoryPolicy(true, 40, true, RuntimeAgentMemoryPolicy.WRITE_EXPLICIT_AND_CANDIDATE));
        assertTrue(instruction.contains("我的记忆"));
    }
}
