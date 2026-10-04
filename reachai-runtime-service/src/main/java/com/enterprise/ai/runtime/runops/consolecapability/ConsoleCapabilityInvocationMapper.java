package com.enterprise.ai.runtime.runops.consolecapability;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ConsoleCapabilityInvocationMapper extends BaseMapper<ConsoleCapabilityInvocationEntity> {

    @Select("SELECT * FROM runtime_console_capability_invocation WHERE invocation_id = #{invocationId} LIMIT 1")
    ConsoleCapabilityInvocationEntity selectByInvocationId(@Param("invocationId") String invocationId);

    @Select("SELECT * FROM runtime_console_capability_invocation WHERE invocation_id = #{invocationId} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    ConsoleCapabilityInvocationEntity selectByInvocationIdForUpdate(@Param("invocationId") String invocationId);

    @Select("<script>SELECT trial.* FROM runtime_console_capability_invocation trial "
            + "JOIN (SELECT qualified_name, MAX(id) AS latest_id "
            + "FROM runtime_console_capability_invocation "
            + "WHERE target_type = 'HTTP_API' AND project_id = #{projectId} "
            + "AND project_code = #{projectCode} AND platform_actor_id = #{actorId} "
            + "AND qualified_name IN <foreach collection='qualifiedNames' item='name' open='(' separator=',' close=')'>#{name}</foreach> "
            + "GROUP BY qualified_name) latest ON trial.id = latest.latest_id</script>")
    List<ConsoleCapabilityInvocationEntity> selectLatestHttpApisForActor(@Param("projectId") long projectId,
            @Param("projectCode") String projectCode, @Param("actorId") String actorId,
            @Param("qualifiedNames") List<String> qualifiedNames);
}
