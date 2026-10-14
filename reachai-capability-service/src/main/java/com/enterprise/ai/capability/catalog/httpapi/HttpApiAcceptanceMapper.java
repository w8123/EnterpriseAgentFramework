package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HttpApiAcceptanceMapper extends BaseMapper<HttpApiAcceptanceEntity> {
    @Select("<script>SELECT a.* FROM capability_http_api_acceptance a JOIN "
            + "(SELECT asset_id, MAX(id) latest_id FROM capability_http_api_acceptance WHERE asset_id IN "
            + "<foreach collection='assetIds' item='assetId' open='(' separator=',' close=')'>#{assetId}</foreach> "
            + "GROUP BY asset_id) latest ON a.id = latest.latest_id</script>")
    List<HttpApiAcceptanceEntity> selectLatestByAssetIds(@Param("assetIds") List<Long> assetIds);
}
