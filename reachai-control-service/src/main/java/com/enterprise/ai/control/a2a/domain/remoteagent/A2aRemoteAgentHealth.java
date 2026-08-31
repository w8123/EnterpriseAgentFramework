package com.enterprise.ai.control.a2a.domain.remoteagent;

import com.enterprise.ai.control.a2a.domain.A2aDomainText;

public enum A2aRemoteAgentHealth {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    UNREACHABLE;

    public static A2aRemoteAgentHealth parse(String value) {
        return A2aDomainText.parseEnum(A2aRemoteAgentHealth.class, value, "remoteAgentHealth");
    }
}
