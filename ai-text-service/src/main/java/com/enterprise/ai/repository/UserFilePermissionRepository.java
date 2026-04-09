package com.enterprise.ai.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ai.domain.entity.UserFilePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserFilePermissionRepository extends BaseMapper<UserFilePermission> {

    @Select("SELECT file_id FROM user_file_permission WHERE user_id = #{userId}")
    List<String> selectFileIdsByUserId(@Param("userId") String userId);
}
