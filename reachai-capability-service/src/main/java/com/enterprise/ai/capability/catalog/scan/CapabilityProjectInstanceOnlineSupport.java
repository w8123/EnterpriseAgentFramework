package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * Shared ONLINE evidence: instance status must be ONLINE and heartbeat must be fresh.
 */
public final class CapabilityProjectInstanceOnlineSupport {

    public static final long HEARTBEAT_ONLINE_MINUTES = 5;

    private CapabilityProjectInstanceOnlineSupport() {
    }

    public static boolean isOnline(ProjectInstanceEntity instance) {
        return isOnline(instance, LocalDateTime.now());
    }

    public static boolean isOnline(ProjectInstanceEntity instance, LocalDateTime now) {
        if (instance == null) {
            return false;
        }
        if (!"ONLINE".equalsIgnoreCase(String.valueOf(instance.getStatus()))) {
            return false;
        }
        LocalDateTime lastHeartbeatAt = instance.getLastHeartbeatAt();
        if (lastHeartbeatAt == null || now == null) {
            return false;
        }
        return lastHeartbeatAt.isAfter(now.minusMinutes(HEARTBEAT_ONLINE_MINUTES));
    }

    public static String statusOrNull(ProjectInstanceEntity instance) {
        if (instance == null || !StringUtils.hasText(instance.getStatus())) {
            return null;
        }
        return instance.getStatus().trim();
    }
}
