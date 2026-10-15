package com.enterprise.ai.reach.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ReachAiHttpRegistryTransportTest {

    @Test
    void usesSixtySecondReadTimeoutByDefault() throws Exception {
        ReachAiHttpRegistryTransport transport = new ReachAiHttpRegistryTransport();
        Field field = ReachAiHttpRegistryTransport.class.getDeclaredField("readTimeoutMs");
        field.setAccessible(true);

        assertEquals(60000, field.getInt(transport));
    }

    @Test
    void registryPropertiesConfigureTheActualTransportWhileRegistrationIsDisabled() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ReachAiRegistryAutoConfiguration.class))
                .withPropertyValues("reachai.registry.enabled=false", "reachai.registry.connect-timeout-ms=3000",
                        "reachai.registry.read-timeout-ms=120000")
                .run(context -> {
                    ReachAiHttpRegistryTransport transport = (ReachAiHttpRegistryTransport) context.getBean(ReachAiRegistryTransport.class);
                    assertEquals(3000, timeout(transport, "connectTimeoutMs"));
                    assertEquals(120000, timeout(transport, "readTimeoutMs"));
                });
    }

    @Test
    void rejectsUnboundedOrExcessiveTransportTimeouts() {
        ReachAiHttpRegistryTransport transport = new ReachAiHttpRegistryTransport();
        assertThrows(IllegalArgumentException.class, () -> transport.setReadTimeoutMs(0));
        assertThrows(IllegalArgumentException.class, () -> transport.setReadTimeoutMs(240001));
        assertThrows(IllegalArgumentException.class, () -> transport.setConnectTimeoutMs(0));
        assertThrows(IllegalArgumentException.class, () -> transport.setConnectTimeoutMs(30001));
    }

    @Test
    void rejectsInvalidTimeoutConfigurationAtStartup() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ReachAiRegistryAutoConfiguration.class))
                .withPropertyValues("reachai.registry.enabled=false", "reachai.registry.read-timeout-ms=0")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    private static int timeout(ReachAiHttpRegistryTransport transport, String name) throws Exception {
        Field field = ReachAiHttpRegistryTransport.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(transport);
    }
}
