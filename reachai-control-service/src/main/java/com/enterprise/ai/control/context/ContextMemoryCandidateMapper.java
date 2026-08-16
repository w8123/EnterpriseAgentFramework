package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ContextMemoryCandidateMapper extends BaseMapper<ContextMemoryCandidateEntity> {

    @Select("SELECT * FROM control_context_memory_candidate WHERE id = #{id} FOR UPDATE")
    ContextMemoryCandidateEntity selectByIdForUpdate(@Param("id") Long id);

    @Delete("""
            DELETE FROM control_context_memory_candidate
             WHERE approved_item_id = #{itemId}
                OR conflict_item_id = #{itemId}
            """)
    int deleteRelatedToItem(@Param("itemId") Long itemId);

    @Delete("""
            DELETE FROM control_context_memory_candidate
             WHERE tenant_id = #{tenantId}
               AND user_id = #{runtimeUserId}
               AND memory_lane = 'RUNTIME_USER'
               AND visibility = 'PRIVATE'
            """)
    int deleteAllPrivateForOwner(@Param("tenantId") String tenantId,
                                 @Param("runtimeUserId") String runtimeUserId);
}
