package com.enterprise.ai.runtime.workflow;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "reachai.mysql.workflowReleaseVerification", matches = "true")
class RuntimeWorkflowReleaseMysqlIT extends RuntimeWorkflowReleasePersistenceTest {
    @Override protected boolean useMysql() { return true; }
}
