package com.enterprise.ai.reach.spring;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReachAiHttpRegistryTransportTest {

    @Test
    void usesSixtySecondReadTimeoutByDefault() throws Exception {
        ReachAiHttpRegistryTransport transport = new ReachAiHttpRegistryTransport();
        Field field = ReachAiHttpRegistryTransport.class.getDeclaredField("readTimeoutMs");
        field.setAccessible(true);

        assertEquals(60000, field.getInt(transport));
    }
}
