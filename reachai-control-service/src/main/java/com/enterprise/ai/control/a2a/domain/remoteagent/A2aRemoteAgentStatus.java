package com.enterprise.ai.control.a2a.domain.remoteagent;

import com.enterprise.ai.control.a2a.domain.A2aDomainText;

public enum A2aRemoteAgentStatus {
    DISCOVERED,
    VERIFYING,
    REVIEW_REQUIRED,
    TRUSTED,
    DISABLED,
    QUARANTINED,
    ARCHIVED;

    public static A2aRemoteAgentStatus parse(String value) {
        return A2aDomainText.parseEnum(A2aRemoteAgentStatus.class, value, "remoteAgentStatus");
    }
}
