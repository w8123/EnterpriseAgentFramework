package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityProjectInstanceOnlineSupportTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 7, 18, 12, 0);

    @Test
    void onlineFreshHeartbeatIsOnline() {
        assertTrue(CapabilityProjectInstanceOnlineSupport.isOnline(instance("ONLINE", now.minusMinutes(1)), now));
    }

    @Test
    void onlineExpiredHeartbeatIsNotOnline() {
        assertFalse(CapabilityProjectInstanceOnlineSupport.isOnline(instance("ONLINE", now.minusMinutes(10)), now));
    }

    @Test
    void offlineFreshHeartbeatIsNotOnline() {
        assertFalse(CapabilityProjectInstanceOnlineSupport.isOnline(instance("OFFLINE", now.minusMinutes(1)), now));
    }

    @Test
    void disabledFreshHeartbeatIsNotOnline() {
        assertFalse(CapabilityProjectInstanceOnlineSupport.isOnline(instance("DISABLED", now.minusMinutes(1)), now));
    }

    @Test
    void missingInstanceIsNotOnline() {
        assertFalse(CapabilityProjectInstanceOnlineSupport.isOnline(null, now));
    }

    private ProjectInstanceEntity instance(String status, LocalDateTime heartbeatAt) {
        ProjectInstanceEntity entity = new ProjectInstanceEntity();
        entity.setStatus(status);
        entity.setLastHeartbeatAt(heartbeatAt);
        return entity;
    }
}
