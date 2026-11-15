package com.enterprise.ai.runtime.supervisor;

import java.util.Optional;

/** Evaluation identity supplied by the evaluation owner; ordinary runs never use this account. */
public interface SupervisorProjectEvalSettings {
    Optional<EvalAccount> evalAccount(String projectCode);

    record EvalAccount(String tenantId, String userId) { }
}
