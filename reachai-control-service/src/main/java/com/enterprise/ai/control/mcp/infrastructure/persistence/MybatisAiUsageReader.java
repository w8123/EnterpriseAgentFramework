package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.mcp.application.usage.AiUsageReader;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Repository
public class MybatisAiUsageReader implements AiUsageReader {

    /** Bounds the IN lists; the page shows at most a few hundred people. */
    private static final int MAX_KEYS = 500;

    private final AiUsageMapper mapper;
    private final AiToolConnectionMapper connectionMapper;

    public MybatisAiUsageReader(AiUsageMapper mapper, AiToolConnectionMapper connectionMapper) {
        this.mapper = mapper;
        this.connectionMapper = connectionMapper;
    }

    @Override
    public List<CallRow> employeeCalls(LocalDateTime since, String projectCode) {
        return mapper.selectEmployeeCalls(since, projectCode).stream()
                .map(row -> new CallRow(row.getConnectionId(), row.getProjectCode(), row.getToolName(),
                        number(row.getCalls()), number(row.getFailed()), row.getLastAt()))
                .toList();
    }

    @Override
    public List<ApprovalRow> approvals(LocalDateTime since, String projectCode) {
        return mapper.selectApprovals(since, projectCode).stream()
                .map(row -> new ApprovalRow(row.getConnectionId(), row.getBusinessUserId(), row.getAppId(),
                        row.getExternalUserId(), row.getUserName(), row.getToolKind(), row.getProjectCode(),
                        row.getSystemName(), row.getToolName(), row.getToolDescription(), row.getState(),
                        number(row.getTotal()), row.getLastAt()))
                .toList();
    }

    @Override
    public List<ConnectionRow> connections(Collection<Long> connectionIds) {
        List<Long> ids = connectionIds.stream().filter(Objects::nonNull).distinct().limit(MAX_KEYS).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return connectionMapper.selectList(Wrappers.<AiToolConnectionEntity>lambdaQuery()
                        .select(AiToolConnectionEntity::getId, AiToolConnectionEntity::getBusinessUserId,
                                AiToolConnectionEntity::getAppId, AiToolConnectionEntity::getExternalUserId,
                                AiToolConnectionEntity::getUserName, AiToolConnectionEntity::getToolKind)
                        .in(AiToolConnectionEntity::getId, ids))
                .stream()
                .map(row -> new ConnectionRow(row.getId(), row.getBusinessUserId(), row.getAppId(),
                        row.getExternalUserId(), row.getUserName(), row.getToolKind()))
                .toList();
    }

    @Override
    public long activeConnections(String projectCode) {
        return mapper.countActiveConnections(projectCode, LocalDateTime.now());
    }

    @Override
    public long capturedSkillCount(LocalDateTime since, String projectCode) {
        return mapper.countCapturedSkills(since, projectCode);
    }

    @Override
    public List<SkillRow> capturedSkills(LocalDateTime since, String projectCode, int limit) {
        return mapper.selectCapturedSkills(since, projectCode, Math.max(1, Math.min(limit, 50))).stream()
                .map(row -> new SkillRow(row.getSkillId(), row.getStandardName(), row.getDisplayName(),
                        row.getVersion(), row.getStatus(), row.getProjectCode(), row.getCreatedBy(),
                        row.getCreatedAt()))
                .toList();
    }

    @Override
    public List<AccountRow> accounts(Collection<AccountKey> keys) {
        List<AccountKey> wanted = keys.stream()
                .filter(key -> key.appId() != null && key.externalUserId() != null)
                .distinct().limit(MAX_KEYS).toList();
        if (wanted.isEmpty()) {
            return List.of();
        }
        Set<String> appIds = new LinkedHashSet<>();
        Set<String> userIds = new LinkedHashSet<>();
        wanted.forEach(key -> {
            appIds.add(key.appId());
            userIds.add(key.externalUserId());
        });
        Set<AccountKey> exact = Set.copyOf(wanted);
        return mapper.selectAccounts(appIds, userIds).stream()
                .filter(row -> exact.contains(new AccountKey(row.getAppId(), row.getExternalUserId())))
                .map(row -> new AccountRow(row.getAppId(), row.getExternalUserId(), row.getBusinessUserId(),
                        row.getName()))
                .toList();
    }

    @Override
    public Map<Long, String> businessUserNames(Collection<Long> businessUserIds) {
        List<Long> ids = businessUserIds.stream().filter(Objects::nonNull).distinct().limit(MAX_KEYS).toList();
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        mapper.selectBusinessUserNames(ids).forEach(row -> {
            if (row.getName() != null) {
                names.put(row.getId(), row.getName());
            }
        });
        return names;
    }

    private static long number(Long value) {
        return value == null ? 0 : value;
    }
}
