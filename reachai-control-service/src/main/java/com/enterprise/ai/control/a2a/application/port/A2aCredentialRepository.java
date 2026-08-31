package com.enterprise.ai.control.a2a.application.port;

import com.enterprise.ai.control.a2a.domain.identity.A2aCredential;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface A2aCredentialRepository {
    Optional<A2aCredential> findById(long id);
    Optional<A2aCredential> findUsableByKey(String credentialKey, LocalDateTime now);
    Optional<A2aCredential> findUsableByKeyAndVersion(String credentialKey, int versionNo, LocalDateTime now);
    boolean existsByKey(String credentialKey);
    int nextVersion(String credentialKey);
    Page findPage(String search, String status, int limit, int offset);
    A2aCredential save(A2aCredential credential);
    void markUsed(long credentialId, LocalDateTime usedAt);

    record Page(List<A2aCredential> items, long total) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
