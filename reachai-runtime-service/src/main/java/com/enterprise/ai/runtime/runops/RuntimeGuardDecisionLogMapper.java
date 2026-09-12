package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RuntimeGuardDecisionLogMapper extends BaseMapper<RuntimeGuardDecisionLogEntity> {

    /** 调用方提供 1 至 100 个 Trace；各自保留最早 500 条证据，再保证合并后的时间/ID 顺序。 */
    @Select("""
            <script>
            <choose>
              <when test="traceIds.size() == 1">
                SELECT * FROM runtime_guard_decision_log
                 WHERE trace_id = #{traceIds[0]}
                 ORDER BY created_at ASC, id ASC LIMIT 500
              </when>
              <otherwise>
                <foreach collection="traceIds" item="traceId" separator=" UNION ALL ">
                  (SELECT * FROM runtime_guard_decision_log
                    WHERE trace_id = #{traceId}
                    ORDER BY created_at ASC, id ASC LIMIT 500)
                </foreach>
                ORDER BY trace_id ASC, created_at ASC, id ASC
              </otherwise>
            </choose>
            </script>
            """)
    List<RuntimeGuardDecisionLogEntity> selectForTraceIds(@Param("traceIds") List<String> traceIds);
}
