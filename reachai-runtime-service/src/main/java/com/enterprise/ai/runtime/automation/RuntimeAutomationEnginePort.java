package com.enterprise.ai.runtime.automation;

interface RuntimeAutomationEnginePort {
    void upsert(RuntimeAutomationEntity automation, RuntimeAutomationVersionEntity version);

    void cancel(String automationKey);

    String engineName();
}
