package com.enterprise.ai.common.capability;

/** Fail-closed error taxonomy used by Runtime retry and RunOps projection. */
public enum CapabilityInvocationFailureCategory {
    NONE,
    NOT_FOUND,
    DISABLED,
    POLICY_REJECTED,
    IDENTITY_REQUIRED,
    CONFIGURATION_INVALID,
    BUSINESS_RESPONSE,
    TIMEOUT,
    CONNECTIVITY,
    RESPONSE_INVALID,
    INTERNAL
}
