package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExternalUserBindingMapper extends BaseMapper<ExternalUserBindingEntity> {
    @Select("SELECT * FROM control_external_user_binding WHERE id = #{id} FOR UPDATE")
    ExternalUserBindingEntity selectByIdForUpdate(@Param("id") Long id);
}
