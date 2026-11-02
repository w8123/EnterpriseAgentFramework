package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/** Runtime controllers read the attested delegate from the current request. */
@Component
final class RuntimeMcpActorScope {
    private final PlatformDelegatedRequestScope delegate = new PlatformDelegatedRequestScope();

    ResponseEntity<?> run(PlatformAuthenticatedSession actor, Supplier<ResponseEntity<?>> action) {
        return delegate.run(actor, request -> action.get());
    }
}
