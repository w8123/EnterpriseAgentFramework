package com.enterprise.ai.control.context;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalMemoryLifecycleSchedulerTest {

    @Test
    void recordsSuccessfulRunsAndExpiredCount() {
        PersonalMemoryService service = mock(PersonalMemoryService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(service.expireDue(50)).thenReturn(3);
        PersonalMemoryLifecycleScheduler scheduler =
                new PersonalMemoryLifecycleScheduler(service, registry, true, 50);

        scheduler.expireDue();

        verify(service).expireDue(50);
        assertEquals(1.0d, registry.get("reachai.personal_memory.lifecycle.runs")
                .tag("outcome", "success").counter().count());
        assertEquals(3.0d, registry.get("reachai.personal_memory.lifecycle.expired")
                .summary().totalAmount());
    }

    @Test
    void disabledSchedulerDoesNotTouchCanonicalMemory() {
        PersonalMemoryService service = mock(PersonalMemoryService.class);
        PersonalMemoryLifecycleScheduler scheduler = new PersonalMemoryLifecycleScheduler(
                service, new SimpleMeterRegistry(), false, 50);

        scheduler.expireDue();

        verify(service, never()).expireDue(50);
    }
}
