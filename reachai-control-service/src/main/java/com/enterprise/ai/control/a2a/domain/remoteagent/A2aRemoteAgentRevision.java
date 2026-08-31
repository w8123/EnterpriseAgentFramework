package com.enterprise.ai.control.a2a.domain.remoteagent;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;

import java.time.LocalDateTime;
import java.util.List;

public record A2aRemoteAgentRevision(
        Long id,
        long remoteAgentId,
        int revisionNo,
        A2aRemoteCardSnapshot card,
        String tlsIdentitySha256,
        String networkEvidenceJson,
        String httpEtag,
        String httpLastModified,
        A2aRemoteRevisionReviewStatus reviewStatus,
        LocalDateTime discoveredAt,
        String reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt) {

    public A2aRemoteAgentRevision {
        if (remoteAgentId <= 0 || revisionNo <= 0 || card == null || reviewStatus == null
                || discoveredAt == null) {
            throw new IllegalArgumentException("remote Agent revision metadata is invalid");
        }
    }

    public A2aRemoteAgentRevision approve(String actor, LocalDateTime now) {
        if (reviewStatus != A2aRemoteRevisionReviewStatus.PENDING) {
            throw new A2aDomainException("A2A_REMOTE_REVISION_NOT_PENDING",
                    "only a pending remote Agent revision can be approved");
        }
        return new A2aRemoteAgentRevision(
                id, remoteAgentId, revisionNo, card, tlsIdentitySha256, networkEvidenceJson,
                httpEtag, httpLastModified, A2aRemoteRevisionReviewStatus.APPROVED,
                discoveredAt, actor, now, createdAt);
    }

    public A2aRemoteAgentRevision reject(String actor, LocalDateTime now) {
        if (reviewStatus != A2aRemoteRevisionReviewStatus.PENDING) {
            throw new A2aDomainException("A2A_REMOTE_REVISION_NOT_PENDING",
                    "only a pending remote Agent revision can be rejected");
        }
        return new A2aRemoteAgentRevision(
                id, remoteAgentId, revisionNo, card, tlsIdentitySha256, networkEvidenceJson,
                httpEtag, httpLastModified, A2aRemoteRevisionReviewStatus.REJECTED,
                discoveredAt, actor, now, createdAt);
    }

    public A2aRemoteInterface requireInterface(String interfaceKey) {
        return card.supportedInterfaces().stream()
                .filter(value -> value.interfaceKey().equals(interfaceKey))
                .filter(A2aRemoteInterface::supportedByFirstRelease)
                .findFirst()
                .orElseThrow(() -> new A2aDomainException("A2A_REMOTE_INTERFACE_NOT_SUPPORTED",
                        "preferred interface is not an A2A 1.0 HTTP+JSON interface"));
    }

    public List<A2aRemoteInterface> callableInterfaces() {
        return card.supportedInterfaces().stream()
                .filter(A2aRemoteInterface::supportedByFirstRelease)
                .toList();
    }
}
