package com.enterprise.ai.runtime.supervisor;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import reactor.core.publisher.Mono;

import java.util.function.BooleanSupplier;

/** A persisted interaction ends the reasoning loop until the owning resume entry is called. */
final class SupervisorInteractionPauseHook implements Hook {
    private final BooleanSupplier waiting;

    SupervisorInteractionPauseHook(BooleanSupplier waiting) {
        this.waiting = waiting;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent acting && waiting.getAsBoolean()) {
            acting.stopAgent();
        }
        return Mono.just(event);
    }
}
