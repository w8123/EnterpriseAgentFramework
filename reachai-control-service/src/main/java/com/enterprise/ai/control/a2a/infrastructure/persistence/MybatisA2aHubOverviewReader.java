package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.a2a.application.overview.A2aHubOverviewReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MybatisA2aHubOverviewReader implements A2aHubOverviewReader {

    private final A2aPublicationMapper publicationMapper;
    private final A2aRemoteAgentMapper remoteAgentMapper;
    private final A2aTaskMapper taskMapper;
    private final A2aCredentialMapper credentialMapper;
    private final A2aConformanceRunMapper conformanceRunMapper;
    private final A2aTransportEventMapper transportEventMapper;

    @Override
    public Snapshot read(LocalDateTime since, LocalDateTime credentialExpiryCutoff) {
        A2aTransportMetricsRow transport = transportEventMapper.aggregateSince(since);
        long transportTotal = transport == null || transport.getTotalCount() == null ? 0 : transport.getTotalCount();
        long transportSuccess = transport == null || transport.getSuccessCount() == null ? 0 : transport.getSuccessCount();
        return new Snapshot(
                countPublication("PUBLISHED"),
                countPublication("DRAFT"),
                countPublication("SUSPENDED"),
                countRemoteStatus("TRUSTED"),
                countRemoteStatus("QUARANTINED"),
                countUnhealthyRemoteAgents(),
                countTasksSince(since, null),
                countTasksSince(since, "TASK_STATE_COMPLETED"),
                countTasksSince(since, "TASK_STATE_FAILED"),
                countTasksSince(since, "TASK_STATE_WORKING"),
                countTasksSince(since, "TASK_STATE_INPUT_REQUIRED"),
                countTasksSince(since, "TASK_STATE_AUTH_REQUIRED"),
                countExpiringCredentials(credentialExpiryCutoff),
                countFailedConformanceRuns(since),
                transportTotal,
                transportSuccess,
                transport == null ? null : transport.getAverageLatencyMs());
    }

    private long countPublication(String status) {
        return publicationMapper.selectCount(Wrappers.<A2aPublicationEntity>lambdaQuery()
                .eq(A2aPublicationEntity::getStatus, status));
    }

    private long countRemoteStatus(String status) {
        return remoteAgentMapper.selectCount(Wrappers.<A2aRemoteAgentEntity>lambdaQuery()
                .eq(A2aRemoteAgentEntity::getStatus, status));
    }

    private long countUnhealthyRemoteAgents() {
        return remoteAgentMapper.selectCount(Wrappers.<A2aRemoteAgentEntity>lambdaQuery()
                .in(A2aRemoteAgentEntity::getHealthStatus, List.of("DEGRADED", "UNREACHABLE")));
    }

    private long countTasksSince(LocalDateTime since, String state) {
        return taskMapper.selectCount(Wrappers.<A2aTaskEntity>lambdaQuery()
                .ge(A2aTaskEntity::getSubmittedAt, since)
                .eq(state != null, A2aTaskEntity::getState, state));
    }

    private long countExpiringCredentials(LocalDateTime cutoff) {
        return credentialMapper.selectCount(Wrappers.<A2aCredentialEntity>lambdaQuery()
                .in(A2aCredentialEntity::getStatus, List.of("ACTIVE", "GRACE"))
                .isNotNull(A2aCredentialEntity::getExpiresAt)
                .le(A2aCredentialEntity::getExpiresAt, cutoff));
    }

    private long countFailedConformanceRuns(LocalDateTime since) {
        return conformanceRunMapper.selectCount(Wrappers.<A2aConformanceRunEntity>lambdaQuery()
                .ge(A2aConformanceRunEntity::getCreatedAt, since)
                .in(A2aConformanceRunEntity::getStatus, List.of("FAILED", "ERROR")));
    }
}
