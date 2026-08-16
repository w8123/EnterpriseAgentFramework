package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PlatformUserRoleMapper extends BaseMapper<PlatformUserRoleEntity> {

    /** Serializes changes that could remove the final global platform administrator. */
    @Select("""
            SELECT id, user_id, role_id, scope_type, scope_value, created_at
            FROM control_platform_user_role
            WHERE role_id = #{roleId}
              AND UPPER(scope_type) = 'GLOBAL'
              AND scope_value = '*'
            FOR UPDATE
            """)
    List<PlatformUserRoleEntity> selectGlobalRoleGrantsForUpdate(@Param("roleId") Long roleId);

    /** Locks active global administrators while the bootstrap invariant is evaluated. */
    @Select("""
            SELECT ur.id, ur.user_id, ur.role_id, ur.scope_type, ur.scope_value, ur.created_at
            FROM control_platform_user_role ur
            JOIN control_platform_role r ON r.id = ur.role_id
            JOIN control_platform_user u ON u.id = ur.user_id
            WHERE r.role_code = 'PLATFORM_ADMIN'
              AND UPPER(r.status) = 'ACTIVE'
              AND UPPER(u.status) = 'ACTIVE'
              AND UPPER(ur.scope_type) = 'GLOBAL'
              AND ur.scope_value = '*'
            FOR UPDATE
            """)
    List<PlatformUserRoleEntity> selectActiveGlobalPlatformAdministratorGrantsForUpdate();
}
