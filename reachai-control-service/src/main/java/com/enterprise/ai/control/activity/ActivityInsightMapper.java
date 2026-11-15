package com.enterprise.ai.control.activity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ActivityInsightMapper extends BaseMapper<ActivityInsightEntity> {

    @Update("UPDATE control_activity_insight SET used_count = used_count + 1 WHERE id = #{id} AND project_code = #{projectCode} AND status = 'RECOMMENDED'")
    int incrementUsed(@Param("id") Long id, @Param("projectCode") String projectCode);
}
