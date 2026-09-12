package com.enterprise.ai.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ai.domain.entity.DocumentArtifactLifecycle;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DocumentArtifactLifecycleRepository extends BaseMapper<DocumentArtifactLifecycle> {
    @Insert("""
            INSERT INTO knowledge_document_artifact_lifecycle
                (artifact_id, storage_id, object_key, publication_deadline)
            VALUES (#{id}, #{storage}, #{key}, TIMESTAMPADD(SECOND, 1200, CURRENT_TIMESTAMP))
            """)
    int register(@Param("id") String id, @Param("storage") String storage, @Param("key") String key);

    @Select("SELECT * FROM knowledge_document_artifact_lifecycle WHERE artifact_id = #{id} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    DocumentArtifactLifecycle lock(@Param("id") String id);

    /** Called after the first row lock, so the database clock is sampled after any wait. */
    @Select("""
            SELECT * FROM knowledge_document_artifact_lifecycle
            WHERE artifact_id = #{id} AND state = 'WRITING' AND write_acknowledged = 1
              AND publication_deadline > CURRENT_TIMESTAMP FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    DocumentArtifactLifecycle lockPublishable(@Param("id") String id);

    @Update("""
            UPDATE knowledge_document_artifact_lifecycle
            SET write_acknowledged = 1, next_cleanup_at = CURRENT_TIMESTAMP,
                cleanup_lease_owner = NULL, cleanup_lease_until = NULL
            WHERE artifact_id = #{id} AND write_acknowledged = 0 AND state IN ('WRITING', 'RECLAIMING')
            """)
    int acknowledge(@Param("id") String id);

    @Update("""
            UPDATE knowledge_document_artifact_lifecycle SET state = 'RETAINED'
            WHERE artifact_id = #{id} AND state = 'WRITING' AND write_acknowledged = 1
              AND publication_deadline > CURRENT_TIMESTAMP
            """)
    int retain(@Param("id") String id);

    @Update("""
            UPDATE knowledge_document_artifact_lifecycle
            SET state = 'RECLAIMING', next_cleanup_at = CURRENT_TIMESTAMP,
                cleanup_lease_owner = NULL, cleanup_lease_until = NULL
            WHERE artifact_id = #{id} AND state IN ('WRITING', 'RETAINED')
            """)
    int retire(@Param("id") String id);

    @Select("""
            SELECT * FROM knowledge_document_artifact_lifecycle
            WHERE storage_id = #{storage} AND next_cleanup_at <= CURRENT_TIMESTAMP
              AND (cleanup_lease_until IS NULL OR cleanup_lease_until <= CURRENT_TIMESTAMP)
              AND (state = 'RECLAIMING' OR (state = 'WRITING' AND publication_deadline <= CURRENT_TIMESTAMP))
            ORDER BY next_cleanup_at, artifact_id LIMIT #{limit}
            """)
    List<DocumentArtifactLifecycle> findReclaimable(@Param("storage") String storage, @Param("limit") int limit);

    @Update("""
            UPDATE knowledge_document_artifact_lifecycle
            SET next_cleanup_at = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP)
            WHERE artifact_id = #{id} AND state IN ('WRITING', 'RECLAIMING')
              AND (cleanup_lease_until IS NULL OR cleanup_lease_until <= CURRENT_TIMESTAMP)
            """)
    int deferReferenced(@Param("id") String id);

    @Update("""
            UPDATE knowledge_document_artifact_lifecycle
            SET state = 'RECLAIMING', cleanup_lease_owner = #{owner},
                cleanup_lease_until = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP)
            WHERE artifact_id = #{id} AND storage_id = #{storage} AND next_cleanup_at <= CURRENT_TIMESTAMP
              AND (cleanup_lease_until IS NULL OR cleanup_lease_until <= CURRENT_TIMESTAMP)
              AND (state = 'RECLAIMING' OR (state = 'WRITING' AND publication_deadline <= CURRENT_TIMESTAMP))
            """)
    int claim(@Param("id") String id, @Param("storage") String storage, @Param("owner") String owner);

    @Update("""
            UPDATE knowledge_document_artifact_lifecycle
            SET state = CASE WHEN #{error} IS NULL AND write_acknowledged = 1
                             THEN 'RECLAIMED' ELSE 'RECLAIMING' END,
                next_cleanup_at = TIMESTAMPADD(SECOND, #{delay}, CURRENT_TIMESTAMP),
                cleanup_lease_owner = NULL, cleanup_lease_until = NULL, last_cleanup_error = #{error}
            WHERE artifact_id = #{id} AND state = 'RECLAIMING' AND cleanup_lease_owner = #{owner}
            """)
    int finish(@Param("id") String id, @Param("owner") String owner,
               @Param("delay") int delay, @Param("error") String error);
}
