package com.enterprise.ai.control.a2a.application.port;

import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipal;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface A2aPrincipalRepository {
    Optional<A2aPrincipal> findById(long id);
    Optional<A2aPrincipal> lockActiveById(long id);
    Optional<A2aPrincipal> findActiveByCredential(long credentialId, long trustProfileId);
    Optional<A2aPrincipal> findActiveAnonymous(long trustProfileId);
    boolean existsByKey(String principalKey);
    Page findPage(String search, String status, int limit, int offset);
    A2aPrincipal save(A2aPrincipal principal);
    void markAuthenticated(long principalId, LocalDateTime authenticatedAt);
    void rebindCredential(long oldCredentialId, long newCredentialId);

    record Page(List<A2aPrincipal> items, long total) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
