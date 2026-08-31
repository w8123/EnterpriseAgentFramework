package com.enterprise.ai.control.a2a.application.port;

import com.enterprise.ai.control.a2a.domain.publication.A2aPublication;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublicationRevision;
import com.enterprise.ai.control.a2a.domain.publication.A2aProtocolSkill;

import java.util.List;
import java.util.Optional;

public interface A2aPublicationRepository {

    Optional<A2aPublication> findById(long id);

    Optional<A2aPublicationRevision> findRevision(long publicationId, long revisionId);

    List<A2aPublicationRevision> findRevisions(long publicationId);

    int nextRevisionNo(long publicationId);

    boolean existsByPublicationKey(String publicationKey);

    boolean existsByAgentScope(String agentId, String environment, String tenantScope);

    Page findPage(String search, String status, int limit, int offset);

    A2aPublication save(A2aPublication publication);

    A2aPublicationRevision saveRevision(A2aPublicationRevision revision);

    Optional<PublishedCard> findPublishedCardByHost(String publicHost);

    record Page(List<A2aPublication> items, long total) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    record PublishedCard(
            long publicationId,
            long revisionId,
            String publicationKey,
            String publicHost,
            String agentId,
            long agentConfigVersionId,
            long trustProfileId,
            String tenantScope,
            String environment,
            List<String> defaultInputModes,
            List<String> defaultOutputModes,
            List<A2aProtocolSkill> protocolSkills,
            String agentCardJson,
            String agentCardSha256,
            java.time.LocalDateTime publishedAt) {
        public PublishedCard {
            defaultInputModes = defaultInputModes == null ? List.of() : List.copyOf(defaultInputModes);
            defaultOutputModes = defaultOutputModes == null ? List.of() : List.copyOf(defaultOutputModes);
            protocolSkills = protocolSkills == null ? List.of() : List.copyOf(protocolSkills);
        }
    }
}
