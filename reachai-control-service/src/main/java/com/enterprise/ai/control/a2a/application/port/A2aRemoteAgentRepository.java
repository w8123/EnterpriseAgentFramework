package com.enterprise.ai.control.a2a.application.port;

import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgent;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentRevision;

import java.util.List;
import java.util.Optional;

public interface A2aRemoteAgentRepository {

    boolean existsByKey(String remoteAgentKey);

    boolean existsByCardUrlHash(String tenantScope, String cardUrlSha256);

    Optional<A2aRemoteAgent> findById(long id);

    Optional<A2aRemoteAgentRevision> findRevision(long remoteAgentId, long revisionId);

    Optional<A2aRemoteAgentRevision> findRevisionByHash(long remoteAgentId, String cardSha256);

    Optional<A2aRemoteAgentRevision> findLatestRevision(long remoteAgentId);

    List<A2aRemoteAgentRevision> findRevisions(long remoteAgentId);

    Page findPage(String search, String status, String health, int limit, int offset);

    int nextRevisionNo(long remoteAgentId);

    A2aRemoteAgent save(A2aRemoteAgent remoteAgent);

    A2aRemoteAgentRevision saveRevision(A2aRemoteAgentRevision revision);

    void supersedePendingRevisions(long remoteAgentId, Long excludingRevisionId);

    void supersedeApprovedRevisions(long remoteAgentId, long excludingRevisionId);

    void rebindCredential(long oldCredentialId, long newCredentialId);

    record Page(List<A2aRemoteAgent> items, long total) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
