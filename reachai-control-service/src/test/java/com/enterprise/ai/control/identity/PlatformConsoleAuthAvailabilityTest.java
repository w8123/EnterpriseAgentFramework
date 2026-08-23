package com.enterprise.ai.control.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformConsoleAuthAvailabilityTest {

    @Test
    void defaultsToLocalLoginAndTheOpenSourceDevelopmentAdministrator() {
        PlatformAuthProperties properties = new PlatformAuthProperties();

        assertTrue(properties.localPasswordLoginEnabled());
        assertTrue(properties.getLocal().getBootstrapAdmin().isEnabled());
        assertEquals("admin", properties.getLocal().getBootstrapAdmin().getUsername());
        assertEquals("admin123", properties.getLocal().getBootstrapAdmin().getPassword());
    }

    @Test
    void localLoginNeedsBothEnvironmentAndAnActiveLocalProvider() {
        PlatformAuthProperties properties = localProperties(false);
        PlatformAuthProviderMapper mapper = mock(PlatformAuthProviderMapper.class);
        PlatformConsoleAuthAvailability availability = new PlatformConsoleAuthAvailability(properties, mapper);

        PlatformConsoleAuthAvailability.Availability disabled = availability.current();

        assertFalse(disabled.available());
        assertEquals(PlatformConsoleAuthAvailability.NOT_CONFIGURED, disabled.code());
        verify(mapper, never()).selectOne(any());

        properties.getLocal().setEnabled(true);
        PlatformAuthProviderEntity local = new PlatformAuthProviderEntity();
        local.setProviderCode("LOCAL");
        local.setStatus("ACTIVE");
        when(mapper.selectOne(any())).thenReturn(local);

        assertTrue(availability.current().available());
    }

    @Test
    void unsupportedConfiguredProviderIsReportedAsUnavailableWithoutReadingSecrets() {
        PlatformAuthProperties properties = localProperties(true);
        properties.setProvider("OIDC");
        PlatformAuthProviderMapper mapper = mock(PlatformAuthProviderMapper.class);
        PlatformConsoleAuthAvailability availability = new PlatformConsoleAuthAvailability(properties, mapper);

        PlatformConsoleAuthAvailability.Availability state = availability.current();

        assertFalse(state.available());
        assertEquals(PlatformConsoleAuthAvailability.PROVIDER_NOT_IMPLEMENTED, state.code());
        verify(mapper, never()).selectOne(any());
        assertEquals(Status.DOWN, new PlatformConsoleAuthHealthIndicator(availability).health().getStatus());
    }

    private PlatformAuthProperties localProperties(boolean localEnabled) {
        PlatformAuthProperties properties = new PlatformAuthProperties();
        properties.setProvider("LOCAL");
        properties.getLocal().setEnabled(localEnabled);
        return properties;
    }
}
