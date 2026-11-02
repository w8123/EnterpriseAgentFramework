package com.enterprise.ai.control.mcp.application.usage;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Aggregated, body-free reads behind 「AI 使用情况」. Every list is already grouped by the database. */
public interface AiUsageReader {

    /** tools/call made from employees' own AI tools, grouped by connection, system and tool. */
    List<CallRow> employeeCalls(LocalDateTime since, String projectCode);

    /** Write approvals created since {@code since}, grouped by employee, AI tool, system, tool and state. */
    List<ApprovalRow> approvals(LocalDateTime since, String projectCode);

    List<ConnectionRow> connections(Collection<Long> connectionIds);

    long activeConnections(String projectCode);

    long capturedSkillCount(LocalDateTime since, String projectCode);

    List<SkillRow> capturedSkills(LocalDateTime since, String projectCode, int limit);

    /** Business-system accounts → ReachAI business user, so one person's chats and AI-tool calls line up. */
    List<AccountRow> accounts(Collection<AccountKey> keys);

    Map<Long, String> businessUserNames(Collection<Long> businessUserIds);

    record CallRow(Long connectionId, String projectCode, String toolName, long calls, long failed,
                   LocalDateTime lastAt) {
    }

    record ApprovalRow(Long connectionId, Long businessUserId, String appId, String externalUserId,
                       String userName, String toolKind, String projectCode, String systemName, String toolName,
                       String toolDescription, String state, long count, LocalDateTime lastAt) {
    }

    record ConnectionRow(Long id, Long businessUserId, String appId, String externalUserId, String userName,
                         String toolKind) {
    }

    record SkillRow(Long skillId, String standardName, String displayName, String version, String status,
                    String projectCode, String createdBy, LocalDateTime createdAt) {
    }

    record AccountKey(String appId, String externalUserId) {
    }

    record AccountRow(String appId, String externalUserId, Long businessUserId, String name) {
    }
}
