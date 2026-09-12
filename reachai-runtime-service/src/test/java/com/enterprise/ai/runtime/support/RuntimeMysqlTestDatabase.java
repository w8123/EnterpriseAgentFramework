package com.enterprise.ai.runtime.support;

import com.enterprise.ai.common.testing.ClonedDevelopmentMysqlDatabase;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/** Explicit development verification for the Workflow draft and its submission receipt. */
public final class RuntimeMysqlTestDatabase extends ClonedDevelopmentMysqlDatabase {
    private static final Set<String> ALLOWED = Set.of("runtime_workflow", "runtime_workflow_draft_submission");

    public RuntimeMysqlTestDatabase(List<String> tables) throws SQLException {
        super("reachai.mysql.workflowDraftVerification", "audit_workflow", "runtime_", checked(tables));
    }

    private static List<String> checked(List<String> tables) {
        if (tables == null || !ALLOWED.containsAll(tables)) throw new IllegalArgumentException("Unexpected Workflow verification tables");
        return tables;
    }
}
