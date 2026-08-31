package com.enterprise.ai.runtime.a2a;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeA2aExecutionRegistryTest {

    private final RuntimeA2aExecutionRegistry registry = new RuntimeA2aExecutionRegistry();

    @Test
    void cancelBeforeStartCreatesConsumableTombstone() {
        var cancel = registry.cancel("exec-1", "tenant-a", "principal-a");
        assertThat(cancel.accepted()).isTrue();
        assertThat(cancel.active()).isFalse();

        var start = registry.start("exec-1", "tenant-a", "principal-a");
        assertThat(start.preCancelled()).isTrue();
        assertThat(start.cancellation().isCancelled()).isTrue();
        assertThat(registry.size()).isZero();
    }

    @Test
    void activeCancelSignalsTheExactExecutionToken() {
        var start = registry.start("exec-2", "tenant-a", "principal-a");

        var cancel = registry.cancel("exec-2", "tenant-a", "principal-a");

        assertThat(cancel.active()).isTrue();
        assertThat(start.cancellation().isCancelled()).isTrue();
        registry.finish("exec-2", start.cancellation());
        assertThat(registry.size()).isZero();
    }

    @Test
    void duplicateActiveDispatchAndCrossPrincipalCancelAreRejected() {
        registry.start("exec-3", "tenant-a", "principal-a");

        assertThatThrownBy(() -> registry.start("exec-3", "tenant-a", "principal-a"))
                .isInstanceOf(RuntimeA2aExecutionRegistry.RegistryException.class)
                .extracting(error -> ((RuntimeA2aExecutionRegistry.RegistryException) error).code())
                .isEqualTo("A2A_RUNTIME_EXECUTION_ACTIVE");
        assertThatThrownBy(() -> registry.cancel("exec-3", "tenant-a", "principal-b"))
                .isInstanceOf(RuntimeA2aExecutionRegistry.RegistryException.class)
                .extracting(error -> ((RuntimeA2aExecutionRegistry.RegistryException) error).code())
                .isEqualTo("A2A_RUNTIME_EXECUTION_FORBIDDEN");
    }
}
