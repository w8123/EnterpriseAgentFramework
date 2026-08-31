package com.enterprise.ai.control.a2a.domain.remoteagent;

public record A2aRemoteCapabilities(
        boolean streaming,
        boolean pushNotifications,
        boolean extendedAgentCard) {
}
