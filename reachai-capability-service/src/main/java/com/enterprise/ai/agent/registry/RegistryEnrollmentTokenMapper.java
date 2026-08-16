package com.enterprise.ai.agent.registry;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RegistryEnrollmentTokenMapper extends BaseMapper<RegistryEnrollmentTokenEntity> {

    @Select("SELECT * FROM capability_registry_enrollment_token WHERE token_digest = #{tokenDigest} FOR UPDATE")
    RegistryEnrollmentTokenEntity selectByDigestForUpdate(@Param("tokenDigest") String tokenDigest);
}
