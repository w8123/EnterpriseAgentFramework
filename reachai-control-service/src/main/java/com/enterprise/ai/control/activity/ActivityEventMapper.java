package com.enterprise.ai.control.activity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ActivityEventMapper extends BaseMapper<ActivityEventEntity> {

    String SCOPE = """
            WHERE e.occurred_at >= #{since}
            <if test="projectCode != null"> AND e.project_code = #{projectCode}</if>
            """;

    @Select("<script>SELECT COUNT(*) AS total, COUNT(DISTINCT e.project_code, COALESCE(e.tenant_id, ''), e.user_id) AS people,"
            + " COUNT(DISTINCT e.project_code, e.operation_key) AS operations,"
            + " COALESCE(SUM(CASE WHEN e.outcome = 'FAILED' THEN 1 ELSE 0 END), 0) AS failures,"
            + " COALESCE(SUM(CASE WHEN e.keyword IS NOT NULL THEN 1 ELSE 0 END), 0) AS searches,"
            + " MAX(e.occurred_at) AS lastAt"
            + " FROM control_activity_event e " + SCOPE + "</script>")
    Map<String, Object> summary(@Param("projectCode") String projectCode, @Param("since") LocalDateTime since);

    @Select("<script>SELECT e.project_code AS projectCode, e.operation_key AS operationKey,"
            + " MAX(e.operation_name) AS operationName, COUNT(*) AS total, COUNT(DISTINCT COALESCE(e.tenant_id, ''), e.user_id) AS people,"
            + " SUM(CASE WHEN e.outcome = 'FAILED' THEN 1 ELSE 0 END) AS failures,"
            + " ROUND(AVG(e.duration_ms)) AS avgDurationMs, MAX(e.occurred_at) AS lastAt"
            + " FROM control_activity_event e " + SCOPE
            + " GROUP BY e.project_code, e.operation_key ORDER BY total DESC LIMIT #{limit}</script>")
    List<Map<String, Object>> topOperations(@Param("projectCode") String projectCode,
                                            @Param("since") LocalDateTime since, @Param("limit") int limit);

    @Select("<script>SELECT e.project_code AS projectCode, e.keyword AS keyword, COUNT(*) AS total,"
            + " COUNT(DISTINCT COALESCE(e.tenant_id, ''), e.user_id) AS people, MAX(e.operation_name) AS operationName,"
            + " SUM(CASE WHEN e.outcome = 'FAILED' THEN 1 ELSE 0 END) AS failures, MAX(e.occurred_at) AS lastAt"
            + " FROM control_activity_event e " + SCOPE + " AND e.keyword IS NOT NULL"
            + " GROUP BY e.project_code, e.keyword ORDER BY total DESC LIMIT #{limit}</script>")
    List<Map<String, Object>> topKeywords(@Param("projectCode") String projectCode,
                                          @Param("since") LocalDateTime since, @Param("limit") int limit);

    @Select("<script>SELECT e.project_code AS projectCode, e.operation_key AS operationKey,"
            + " MAX(e.operation_name) AS operationName, e.error_message AS errorMessage, COUNT(*) AS total,"
            + " COUNT(DISTINCT COALESCE(e.tenant_id, ''), e.user_id) AS people, MAX(e.page_path) AS pagePath, MAX(e.occurred_at) AS lastAt"
            + " FROM control_activity_event e " + SCOPE + " AND e.outcome = 'FAILED'"
            + " GROUP BY e.project_code, e.operation_key, e.error_message ORDER BY total DESC LIMIT #{limit}</script>")
    List<Map<String, Object>> problems(@Param("projectCode") String projectCode,
                                       @Param("since") LocalDateTime since, @Param("limit") int limit);

    @Select("<script>SELECT e.project_code AS projectCode, e.tenant_id AS tenantId, e.user_id AS userId, MAX(e.user_name) AS userName,"
            + " COUNT(*) AS total, COUNT(DISTINCT e.operation_key) AS operations,"
            + " SUM(CASE WHEN e.outcome = 'FAILED' THEN 1 ELSE 0 END) AS failures,"
            + " SUM(CASE WHEN e.keyword IS NOT NULL THEN 1 ELSE 0 END) AS searches, MAX(e.occurred_at) AS lastAt"
            + " FROM control_activity_event e " + SCOPE
            + " GROUP BY e.project_code, e.tenant_id, e.user_id ORDER BY total DESC LIMIT #{limit}</script>")
    List<Map<String, Object>> people(@Param("projectCode") String projectCode,
                                     @Param("since") LocalDateTime since, @Param("limit") int limit);

    @Select("<script>SELECT DATE(e.occurred_at) AS day, COUNT(*) AS total,"
            + " SUM(CASE WHEN e.outcome = 'FAILED' THEN 1 ELSE 0 END) AS failures"
            + " FROM control_activity_event e " + SCOPE + " GROUP BY DATE(e.occurred_at) ORDER BY day</script>")
    List<Map<String, Object>> byDay(@Param("projectCode") String projectCode, @Param("since") LocalDateTime since);

    @Select("SELECT id, project_code, tenant_id, user_id, operation_key, operation_name, page_path, keyword, outcome,"
            + " error_message, occurred_at FROM control_activity_event"
            + " WHERE project_code = #{projectCode} AND occurred_at >= #{since}"
            + " ORDER BY tenant_id, user_id, occurred_at, id LIMIT #{limit}")
    List<ActivityEventEntity> forMining(@Param("projectCode") String projectCode,
                                        @Param("since") LocalDateTime since, @Param("limit") int limit);

    @Delete("DELETE FROM control_activity_event WHERE project_code = #{projectCode} AND occurred_at < #{before} LIMIT #{limit}")
    int deleteProjectBefore(@Param("projectCode") String projectCode, @Param("before") LocalDateTime before,
                            @Param("limit") int limit);

    @Delete("DELETE FROM control_activity_event WHERE occurred_at < #{before}"
            + " AND project_code NOT IN (SELECT s.project_code FROM control_activity_setting s) LIMIT #{limit}")
    int deleteUnconfiguredBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
