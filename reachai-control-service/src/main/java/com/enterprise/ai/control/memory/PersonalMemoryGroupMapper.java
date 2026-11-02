package com.enterprise.ai.control.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface PersonalMemoryGroupMapper extends BaseMapper<PersonalMemoryGroupEntity> {

    @Select("SELECT * FROM control_personal_memory_group WHERE id = #{id} FOR UPDATE")
    PersonalMemoryGroupEntity selectByIdForUpdate(@Param("id") Long id);

    /** Memory counts per group for one owner; rows with a null group are not yet filed. */
    @Select("""
            SELECT scope_key AS scopeKey, group_id AS groupId, COUNT(*) AS total
              FROM control_personal_memory
             WHERE tenant_id = #{tenantId}
               AND owner_id = #{ownerId}
               AND owner_domain = #{ownerDomain}
             GROUP BY scope_key, group_id
            """)
    List<Map<String, Object>> countByGroup(@Param("tenantId") String tenantId, @Param("ownerId") String ownerId,
                                           @Param("ownerDomain") String ownerDomain);
}
