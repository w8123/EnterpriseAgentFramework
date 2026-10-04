package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HttpApiAssetMapper extends BaseMapper<HttpApiAssetEntity> {

    @Select("SELECT * FROM capability_http_api_asset WHERE id = #{id} FOR UPDATE")
    HttpApiAssetEntity selectByIdForUpdate(@Param("id") Long id);
}
