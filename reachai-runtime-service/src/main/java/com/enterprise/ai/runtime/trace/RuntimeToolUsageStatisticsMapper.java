package com.enterprise.ai.runtime.trace;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RuntimeToolUsageStatisticsMapper {
    @Select("SELECT MAX(id) FROM runtime_tool_call_log WHERE create_time >= #{from}")
    @Options(useCache = false)
    Long maximumUsageId(@Param("from") LocalDateTime from);

    @SelectProvider(type = RuntimeToolUsageSql.class, method = "page")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Row> findUsagePage(@Param("from") LocalDateTime from,
                            @Param("ceiling") long ceiling,
                            @Param("afterTime") LocalDateTime afterTime,
                            @Param("afterId") Long afterId,
                            @Param("whitespace") List<String> whitespace);

    record Row(long id, LocalDateTime createdAt, String traceId, String intentType,
               String agentName, boolean retrieval) { }
}
