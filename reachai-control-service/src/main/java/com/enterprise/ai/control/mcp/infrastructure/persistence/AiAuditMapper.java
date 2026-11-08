package com.enterprise.ai.control.mcp.infrastructure.persistence;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** Row-level reads for the audit export; never selects request, response or argument bodies. */
@Mapper
public interface AiAuditMapper {

    @Select("""
            <script>
            SELECT l.created_at AS createdAt, l.project_code AS projectCode, l.tool_name AS toolName,
                   l.success AS success, l.error_category AS errorCategory, l.run_id AS runId, l.trace_id AS traceId,
                   l.external_user_id AS externalUserId, l.tool_client_name AS toolClientName,
                   c.app_id AS appId, c.user_name AS userName, c.tool_kind AS toolKind, c.business_user_id AS businessUserId
            FROM control_mcp_call_log l
            LEFT JOIN control_ai_tool_connection c ON c.id = l.connection_id
            WHERE l.connection_id IS NOT NULL
              AND l.method = 'tools/call'
              AND l.tool_name IS NOT NULL
              AND l.tool_name &lt;&gt; 'reachai_approval_status'
              AND l.created_at &gt;= #{since}
              <if test="projectCode != null">AND l.project_code = #{projectCode}</if>
            ORDER BY l.created_at DESC, l.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<ToolCallRow> selectToolCalls(@Param("since") LocalDateTime since, @Param("projectCode") String projectCode,
                                      @Param("limit") int limit);

    @Select("""
            <script>
            SELECT a.created_at AS createdAt, a.project_code AS projectCode, a.system_name AS systemName,
                   a.tool_name AS toolName, a.tool_description AS toolDescription, a.state AS state,
                   a.decided_by AS decidedBy, a.decided_at AS decidedAt, a.error_code AS errorCode,
                   a.run_id AS runId, a.trace_id AS traceId, a.external_user_id AS externalUserId,
                   a.user_name AS userName, a.tool_kind AS toolKind, a.business_user_id AS businessUserId,
                   c.app_id AS appId
            FROM control_mcp_write_approval a
            LEFT JOIN control_ai_tool_connection c ON c.id = a.connection_id
            WHERE a.created_at &gt;= #{since}
              <if test="projectCode != null">AND a.project_code = #{projectCode}</if>
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<ApprovalRow> selectApprovals(@Param("since") LocalDateTime since, @Param("projectCode") String projectCode,
                                      @Param("limit") int limit);

    @Select("""
            <script>
            SELECT e.created_at AS createdAt, e.event_type AS eventType, e.actor_user_id AS actorUserId,
                   u.username AS username, u.display_name AS displayName, e.details_json AS detailsJson
            FROM control_platform_auth_audit_event e
            LEFT JOIN control_platform_user u ON u.id = e.actor_user_id
            WHERE e.created_at &gt;= #{since}
              AND e.event_type IN
              <foreach collection="eventTypes" item="type" open="(" separator="," close=")">#{type}</foreach>
            ORDER BY e.created_at DESC, e.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<LoginRow> selectLogins(@Param("since") LocalDateTime since, @Param("eventTypes") List<String> eventTypes,
                                @Param("limit") int limit);

    @Data
    class ToolCallRow {
        private LocalDateTime createdAt;
        private String projectCode;
        private String toolName;
        private Boolean success;
        private String errorCategory;
        private String runId;
        private String traceId;
        private String externalUserId;
        private String toolClientName;
        private String appId;
        private String userName;
        private String toolKind;
        private Long businessUserId;
    }

    @Data
    class ApprovalRow {
        private LocalDateTime createdAt;
        private String projectCode;
        private String systemName;
        private String toolName;
        private String toolDescription;
        private String state;
        private String decidedBy;
        private LocalDateTime decidedAt;
        private String errorCode;
        private String runId;
        private String traceId;
        private String externalUserId;
        private String userName;
        private String toolKind;
        private Long businessUserId;
        private String appId;
    }

    @Data
    class LoginRow {
        private LocalDateTime createdAt;
        private String eventType;
        private Long actorUserId;
        private String username;
        private String displayName;
        private String detailsJson;
    }
}
