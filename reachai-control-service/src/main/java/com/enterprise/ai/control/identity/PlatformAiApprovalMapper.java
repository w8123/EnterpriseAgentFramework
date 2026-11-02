package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlatformAiApprovalMapper extends BaseMapper<PlatformAiApprovalEntity> {
    @Select("SELECT * FROM control_platform_ai_approval WHERE connection_id = #{connectionId} "
            + "AND action = #{action} AND request_key = #{requestKey} FOR UPDATE")
    PlatformAiApprovalEntity findByRequestForUpdate(@Param("connectionId") long connectionId,
                                                     @Param("action") String action,
                                                     @Param("requestKey") String requestKey);
}
