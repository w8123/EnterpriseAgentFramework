package com.enterprise.ai.runtime.automation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeAutomationWorkerStateTest {

    @Test
    void completedOneTimeDefinitionStillExecutesItsMaterializedOccurrence() {
        RuntimeAutomationEntity automation = automation("COMPLETED", 12L);
        RuntimeAutomationVersionEntity version = version(12L, "ONCE");

        assertTrue(RuntimeAutomationWorker.scheduledOccurrenceMayExecute(automation, version));
    }

    @Test
    void pausedArchivedOrSupersededDefinitionsDoNotExecuteScheduledOccurrences() {
        assertFalse(RuntimeAutomationWorker.scheduledOccurrenceMayExecute(
                automation("PAUSED", 12L), version(12L, "ONCE")));
        assertFalse(RuntimeAutomationWorker.scheduledOccurrenceMayExecute(
                automation("ARCHIVED", 12L), version(12L, "ONCE")));
        assertFalse(RuntimeAutomationWorker.scheduledOccurrenceMayExecute(
                automation("COMPLETED", 13L), version(12L, "ONCE")));
        assertFalse(RuntimeAutomationWorker.scheduledOccurrenceMayExecute(
                automation("COMPLETED", 12L), version(12L, "CRON")));
    }

    @Test
    void activeCurrentDefinitionRemainsExecutable() {
        assertTrue(RuntimeAutomationWorker.scheduledOccurrenceMayExecute(
                automation("ACTIVE", 12L), version(12L, "CRON")));
    }

    private RuntimeAutomationEntity automation(String status, Long currentVersionId) {
        RuntimeAutomationEntity value = new RuntimeAutomationEntity();
        value.setStatus(status);
        value.setCurrentVersionId(currentVersionId);
        return value;
    }

    private RuntimeAutomationVersionEntity version(Long id, String triggerType) {
        RuntimeAutomationVersionEntity value = new RuntimeAutomationVersionEntity();
        value.setId(id);
        value.setTriggerType(triggerType);
        return value;
    }
}
