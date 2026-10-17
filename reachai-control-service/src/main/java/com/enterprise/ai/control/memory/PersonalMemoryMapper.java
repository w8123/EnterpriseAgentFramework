package com.enterprise.ai.control.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PersonalMemoryMapper extends BaseMapper<PersonalMemoryEntity> {

    @Select("SELECT * FROM control_personal_memory WHERE id = #{id} FOR UPDATE")
    PersonalMemoryEntity selectByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT *
              FROM control_personal_memory
             WHERE tenant_id = #{tenantId}
               AND owner_id = #{ownerId}
             ORDER BY id
             FOR UPDATE
            """)
    List<PersonalMemoryEntity> selectOwnedForUpdate(@Param("tenantId") String tenantId,
                                                    @Param("ownerId") String ownerId);
}
