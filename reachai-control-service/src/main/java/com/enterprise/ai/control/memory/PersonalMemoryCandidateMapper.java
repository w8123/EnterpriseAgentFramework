package com.enterprise.ai.control.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PersonalMemoryCandidateMapper extends BaseMapper<PersonalMemoryCandidateEntity> {

    @Select("SELECT * FROM control_personal_memory_candidate WHERE id = #{id} FOR UPDATE")
    PersonalMemoryCandidateEntity selectByIdForUpdate(@Param("id") Long id);

    @Delete("""
            DELETE FROM control_personal_memory_candidate
             WHERE approved_memory_id = #{memoryId}
                OR conflict_memory_id = #{memoryId}
            """)
    int deleteRelatedToMemory(@Param("memoryId") Long memoryId);

    @Delete("""
            DELETE FROM control_personal_memory_candidate
             WHERE tenant_id = #{tenantId}
               AND owner_id = #{ownerId}
            """)
    int deleteAllForOwner(@Param("tenantId") String tenantId, @Param("ownerId") String ownerId);
}
