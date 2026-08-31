package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface A2aPrincipalMapper extends BaseMapper<A2aPrincipalEntity> {

    @Select("""
            SELECT * FROM control_a2a_principal
            WHERE id = #{id} AND status = 'ACTIVE'
            FOR UPDATE
            """)
    A2aPrincipalEntity lockActiveById(@Param("id") long id);
}
