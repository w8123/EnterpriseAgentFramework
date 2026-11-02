package com.enterprise.ai.control.mcp.infrastructure.persistence;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** Grouped reads for 「AI 使用情况」; never selects request or response bodies. */
@Mapper
public interface AiUsageMapper {

    @Select("""
            <script>
            SELECT l.connection_id AS connectionId, l.project_code AS projectCode, l.tool_name AS toolName,
                   COUNT(*) AS calls,
                   SUM(CASE WHEN l.success = 0 THEN 1 ELSE 0 END) AS failed,
                   MAX(l.created_at) AS lastAt
            FROM control_mcp_call_log l
            WHERE l.connection_id IS NOT NULL
              AND l.method = 'tools/call'
              AND l.tool_name IS NOT NULL
              AND l.tool_name &lt;&gt; 'reachai_approval_status'
              AND l.created_at &gt;= #{since}
              <if test="projectCode != null">AND l.project_code = #{projectCode}</if>
            GROUP BY l.connection_id, l.project_code, l.tool_name
            </script>
            """)
    List<CallRow> selectEmployeeCalls(@Param("since") LocalDateTime since, @Param("projectCode") String projectCode);

    @Select("""
            <script>
            SELECT a.connection_id AS connectionId, a.business_user_id AS businessUserId, MAX(c.app_id) AS appId,
                   a.external_user_id AS externalUserId, MAX(a.user_name) AS userName, a.tool_kind AS toolKind,
                   a.project_code AS projectCode, MAX(a.system_name) AS systemName, a.tool_name AS toolName,
                   MAX(a.tool_description) AS toolDescription, a.state AS state, COUNT(*) AS total,
                   MAX(a.created_at) AS lastAt
            FROM control_mcp_write_approval a
            LEFT JOIN control_ai_tool_connection c ON c.id = a.connection_id
            WHERE a.created_at &gt;= #{since}
              <if test="projectCode != null">AND a.project_code = #{projectCode}</if>
            GROUP BY a.connection_id, a.business_user_id, a.external_user_id, a.tool_kind, a.project_code,
                     a.tool_name, a.state
            </script>
            """)
    List<ApprovalRow> selectApprovals(@Param("since") LocalDateTime since, @Param("projectCode") String projectCode);

    @Select("""
            <script>
            SELECT COUNT(DISTINCT c.id) FROM control_ai_tool_connection c
            <if test="projectCode != null">
              JOIN control_ai_tool_connection_system s ON s.connection_id = c.id AND s.project_code = #{projectCode}
            </if>
            WHERE c.state = 'ACTIVE' AND (c.expires_at IS NULL OR c.expires_at &gt; #{now})
            </script>
            """)
    long countActiveConnections(@Param("projectCode") String projectCode, @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT COUNT(*) FROM control_agent_skill_version v
            JOIN control_agent_skill s ON s.id = v.skill_id
            WHERE v.source_type = 'RUN_CAPTURE' AND v.created_at &gt;= #{since}
              <if test="projectCode != null">AND s.project_code = #{projectCode}</if>
            </script>
            """)
    long countCapturedSkills(@Param("since") LocalDateTime since, @Param("projectCode") String projectCode);

    @Select("""
            <script>
            SELECT s.id AS skillId, s.standard_name AS standardName, s.display_name AS displayName,
                   v.version AS version, v.status AS status, s.project_code AS projectCode,
                   v.created_by AS createdBy, v.created_at AS createdAt
            FROM control_agent_skill_version v
            JOIN control_agent_skill s ON s.id = v.skill_id
            WHERE v.source_type = 'RUN_CAPTURE' AND v.created_at &gt;= #{since}
              <if test="projectCode != null">AND s.project_code = #{projectCode}</if>
            ORDER BY v.created_at DESC, v.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<SkillRow> selectCapturedSkills(@Param("since") LocalDateTime since, @Param("projectCode") String projectCode,
                                        @Param("limit") int limit);

    @Select("""
            <script>
            SELECT b.app_id AS appId, b.external_user_id AS externalUserId, b.business_user_id AS businessUserId,
                   b.external_user_name AS name
            FROM control_external_user_binding b
            WHERE b.app_id IN
              <foreach collection="appIds" item="appId" open="(" separator="," close=")">#{appId}</foreach>
              AND b.external_user_id IN
              <foreach collection="externalUserIds" item="userId" open="(" separator="," close=")">#{userId}</foreach>
            </script>
            """)
    List<AccountRow> selectAccounts(@Param("appIds") Collection<String> appIds,
                                    @Param("externalUserIds") Collection<String> externalUserIds);

    @Select("""
            <script>
            SELECT u.id AS id, u.display_name AS name FROM control_business_user u
            WHERE u.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<NameRow> selectBusinessUserNames(@Param("ids") Collection<Long> ids);

    @Data
    class CallRow {
        private Long connectionId;
        private String projectCode;
        private String toolName;
        private Long calls;
        private Long failed;
        private LocalDateTime lastAt;
    }

    @Data
    class ApprovalRow {
        private Long connectionId;
        private Long businessUserId;
        private String appId;
        private String externalUserId;
        private String userName;
        private String toolKind;
        private String projectCode;
        private String systemName;
        private String toolName;
        private String toolDescription;
        private String state;
        private Long total;
        private LocalDateTime lastAt;
    }

    @Data
    class SkillRow {
        private Long skillId;
        private String standardName;
        private String displayName;
        private String version;
        private String status;
        private String projectCode;
        private String createdBy;
        private LocalDateTime createdAt;
    }

    @Data
    class AccountRow {
        private String appId;
        private String externalUserId;
        private Long businessUserId;
        private String name;
    }

    @Data
    class NameRow {
        private Long id;
        private String name;
    }
}
