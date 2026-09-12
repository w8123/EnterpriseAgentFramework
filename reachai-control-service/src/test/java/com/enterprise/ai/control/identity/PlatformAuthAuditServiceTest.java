package com.enterprise.ai.control.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PlatformAuthAuditServiceTest {

    @Test
    void recordsActorSessionAndOnlyCallerSuppliedSafeDetails() {
        PlatformAuthAuditEventMapper mapper = mock(PlatformAuthAuditEventMapper.class);
        PlatformAuthAuditService service = new PlatformAuthAuditService(mapper, new ObjectMapper());
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        PlatformAuthenticatedSession actor = new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user), "pls_7", null, List.of(), List.of(), List.of());

        service.record(actor, "PLATFORM_AUTH_PROVIDER_SAVED", "AUTH_PROVIDER", "1",
                Map.of("providerCode", "LOCAL", "status", "ACTIVE"));

        ArgumentCaptor<PlatformAuthAuditEventEntity> captor = ArgumentCaptor.forClass(PlatformAuthAuditEventEntity.class);
        verify(mapper).insert(captor.capture());
        PlatformAuthAuditEventEntity event = captor.getValue();
        assertTrue(event.getDetailsJson().contains("providerCode"));
        assertFalse(event.getDetailsJson().contains("configJson"));
        assertTrue(event.getActorSessionId().equals("pls_7"));
    }
}
