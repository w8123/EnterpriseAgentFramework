package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void expiredOnlineStatusIncludesBoundaryAndMissingHeartbeat() {
        assertEquals("STALE", CapabilityProjectInstanceOnlineSupport.statusOrNull(instance("ONLINE", now.minusDays(9)), now));
        assertEquals("STALE", CapabilityProjectInstanceOnlineSupport.statusOrNull(instance("ONLINE", now.minusMinutes(5)), now));
        assertEquals("STALE", CapabilityProjectInstanceOnlineSupport.statusOrNull(instance("ONLINE", null), now));
        assertEquals("ONLINE", CapabilityProjectInstanceOnlineSupport.statusOrNull(instance("ONLINE", now.minusMinutes(5).plusSeconds(1)), now));
    }

    @Test
    void effectiveStatusPreservesAdministrativeStatesAndDoesNotMutateObservation() {
        for (String status : new String[]{"OFFLINE", "STALE", "DISABLED"}) {
            assertEquals(status, CapabilityProjectInstanceOnlineSupport.statusOrNull(instance(status, now.minusDays(9)), now));
        }
        ProjectInstanceEntity expired = instance("ONLINE", now.minusDays(9));
        assertEquals("STALE", CapabilityProjectInstanceOnlineSupport.statusOrNull(expired, now));
        assertEquals("ONLINE", expired.getStatus());
        assertNull(CapabilityProjectInstanceOnlineSupport.statusOrNull(null, now));
        assertNull(CapabilityProjectInstanceOnlineSupport.statusOrNull(instance(" ", now), now));
    }

    private ProjectInstanceEntity instance(String status, LocalDateTime heartbeatAt) {
        ProjectInstanceEntity entity = new ProjectInstanceEntity();
        entity.setStatus(status);
        entity.setLastHeartbeatAt(heartbeatAt);
        return entity;
    }
}
