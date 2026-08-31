package com.enterprise.ai.model.catalog;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ModelCatalogSourceMapper extends BaseMapper<ModelCatalogSourceEntity> {

    @Select("""
            SELECT *
            FROM model_catalog_source
            WHERE enabled = 1
            ORDER BY sort_order ASC, id ASC
            """)
    List<ModelCatalogSourceEntity> selectEnabled();

    @Update("""
            UPDATE model_catalog_source
            SET last_checked_at = #{checkedAt},
                last_success_at = #{checkedAt},
                last_http_etag = #{etag},
                last_http_modified = #{lastModified},
                last_content_sha256 = COALESCE(#{contentSha256}, last_content_sha256),
                last_error_code = NULL,
                last_error_message = NULL,
                updated_at = NOW()
            WHERE id = #{sourceId}
            """)
    int markSuccess(@Param("sourceId") Long sourceId,
                    @Param("checkedAt") LocalDateTime checkedAt,
                    @Param("etag") String etag,
                    @Param("lastModified") String lastModified,
                    @Param("contentSha256") String contentSha256);

    @Update("""
            UPDATE model_catalog_source
            SET last_checked_at = #{checkedAt},
                last_error_code = #{errorCode},
                last_error_message = #{errorMessage},
                updated_at = NOW()
            WHERE id = #{sourceId}
            """)
    int markFailure(@Param("sourceId") Long sourceId,
                    @Param("checkedAt") LocalDateTime checkedAt,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage);
}
