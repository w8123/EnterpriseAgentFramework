package com.enterprise.ai.runtime.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RuntimeConversationSessionMapper extends BaseMapper<RuntimeConversationSessionEntity> {

    /**
     * Selects exact due rows using each tenant's policy. Every row is claimed again with
     * optimistic predicates before any external state is erased.
     */
    @Select("""
            SELECT s.*
            FROM runtime_conversation_session s
            LEFT JOIN runtime_session_retention_policy p ON p.tenant_id = s.tenant_id
            WHERE s.legal_hold = 0
              AND (s.turn_lease_expires_at IS NULL OR s.turn_lease_expires_at <= #{now})
              AND (
                    (s.status = 'CLEARING'
                        AND (s.lifecycle_lease_expires_at IS NULL OR s.lifecycle_lease_expires_at <= #{now}))
                 OR (s.status = 'PURGING'
                        AND (s.lifecycle_lease_expires_at IS NULL OR s.lifecycle_lease_expires_at <= #{now}))
                 OR (s.status = 'EXPIRED')
                 OR (s.status = 'ACTIVE'
                        AND TIMESTAMPDIFF(DAY, s.updated_at, #{now}) >=
                            COALESCE(p.active_retention_days, #{defaultActiveRetentionDays}))
                 OR (s.status = 'CLEARED'
                        AND s.cleared_at IS NOT NULL
                        AND TIMESTAMPDIFF(HOUR, s.cleared_at, #{now}) >=
                            COALESCE(p.cleared_retention_hours, #{defaultClearedRetentionHours}))
              )
            ORDER BY
              CASE s.status WHEN 'CLEARING' THEN 0 WHEN 'PURGING' THEN 1 ELSE 2 END,
              COALESCE(s.cleared_at, s.updated_at) ASC,
              s.id ASC
            LIMIT #{limit}
            """)
    List<RuntimeConversationSessionEntity> selectRetentionCandidates(
            @Param("now") LocalDateTime now,
            @Param("defaultActiveRetentionDays") int defaultActiveRetentionDays,
            @Param("defaultClearedRetentionHours") int defaultClearedRetentionHours,
            @Param("limit") int limit);

    /**
     * Bounded owner-erasure scan. The raw user id is used only as an owning-table predicate and
     * must never be returned by the administration API or written to retention audit rows.
     */
    @Select("""
            SELECT s.*
            FROM runtime_conversation_session s
            WHERE s.tenant_id = #{tenantId}
              AND s.user_id = #{runtimeUserId}
            ORDER BY s.id
            LIMIT #{limit}
            """)
    List<RuntimeConversationSessionEntity> selectOwnerEraseCandidates(
            @Param("tenantId") String tenantId,
            @Param("runtimeUserId") String runtimeUserId,
            @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM runtime_conversation_session s
            WHERE s.tenant_id = #{tenantId}
              AND s.user_id = #{runtimeUserId}
            """)
    long countOwnerSessions(
            @Param("tenantId") String tenantId,
            @Param("runtimeUserId") String runtimeUserId);
}
