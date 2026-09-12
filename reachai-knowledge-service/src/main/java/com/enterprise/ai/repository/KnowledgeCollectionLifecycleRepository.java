package com.enterprise.ai.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ai.domain.entity.KnowledgeCollectionLifecycle;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface KnowledgeCollectionLifecycleRepository extends BaseMapper<KnowledgeCollectionLifecycle> {
    @Insert("""
            INSERT INTO knowledge_collection_lifecycle
                (collection_name, knowledge_base_code, dimension, create_deadline)
            VALUES (#{collection}, #{code}, #{dimension}, TIMESTAMPADD(SECOND, 1200, CURRENT_TIMESTAMP))
            """)
    int register(@Param("collection") String collection, @Param("code") String code,
                 @Param("dimension") int dimension);

    @Select("SELECT * FROM knowledge_collection_lifecycle WHERE collection_name=#{collection} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    KnowledgeCollectionLifecycle lock(@Param("collection") String collection);

    // Separate from the first locking read: database time must be sampled after the lock wait.
    @Select("""
            SELECT * FROM knowledge_collection_lifecycle
            WHERE collection_name = #{collection} AND state = 'CREATING'
              AND create_acknowledged = 1 AND create_deadline > CURRENT_TIMESTAMP
            FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    KnowledgeCollectionLifecycle lockPublishable(@Param("collection") String collection);

    @Update("""
            UPDATE knowledge_collection_lifecycle
            SET create_acknowledged = 1, next_cleanup_at = CURRENT_TIMESTAMP,
                cleanup_lease_owner = NULL, cleanup_lease_until = NULL
            WHERE collection_name = #{collection} AND create_acknowledged = 0
              AND state IN ('CREATING', 'RECLAIMING')
            """)
    int acknowledge(@Param("collection") String collection);

    @Update("""
            UPDATE knowledge_collection_lifecycle
            SET state = 'READY', knowledge_base_id = #{kb}
            WHERE collection_name = #{collection} AND state = 'CREATING'
              AND create_acknowledged = 1 AND create_deadline > CURRENT_TIMESTAMP
              AND knowledge_base_id IS NULL
            """)
    int publish(@Param("collection") String collection, @Param("kb") Long kb);

    @Update("""
            UPDATE knowledge_collection_lifecycle
            SET state = 'RECLAIMING', next_cleanup_at = CURRENT_TIMESTAMP,
                cleanup_lease_owner = NULL, cleanup_lease_until = NULL
            WHERE collection_name = #{collection} AND state = 'CREATING'
            """)
    int abandon(@Param("collection") String collection);

    @Update("""
            UPDATE knowledge_collection_lifecycle
            SET state = 'RECLAIMING', next_cleanup_at = CURRENT_TIMESTAMP,
                cleanup_lease_owner = NULL, cleanup_lease_until = NULL
            WHERE collection_name = #{collection} AND state = 'READY'
            """)
    int retire(@Param("collection") String collection);

    @Select("""
            SELECT * FROM knowledge_collection_lifecycle
            WHERE next_cleanup_at <= CURRENT_TIMESTAMP
              AND (cleanup_lease_until IS NULL OR cleanup_lease_until <= CURRENT_TIMESTAMP)
              AND (state = 'RECLAIMING' OR (state = 'CREATING' AND create_deadline <= CURRENT_TIMESTAMP))
            ORDER BY next_cleanup_at, collection_name
            LIMIT #{limit}
            """)
    List<KnowledgeCollectionLifecycle> findReclaimable(@Param("limit") int limit);

    @Update("""
            UPDATE knowledge_collection_lifecycle
            SET next_cleanup_at = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP)
            WHERE collection_name = #{collection} AND state IN ('CREATING', 'RECLAIMING')
              AND (cleanup_lease_until IS NULL OR cleanup_lease_until <= CURRENT_TIMESTAMP)
            """)
    int deferReferenced(@Param("collection") String collection);

    @Update("""
            UPDATE knowledge_collection_lifecycle
            SET state = 'RECLAIMING', cleanup_lease_owner = #{owner},
                cleanup_lease_until = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP)
            WHERE collection_name = #{collection} AND next_cleanup_at <= CURRENT_TIMESTAMP
              AND (cleanup_lease_until IS NULL OR cleanup_lease_until <= CURRENT_TIMESTAMP)
              AND (state = 'RECLAIMING' OR (state = 'CREATING' AND create_deadline <= CURRENT_TIMESTAMP))
            """)
    int claim(@Param("collection") String collection, @Param("owner") String owner);

    @Update("""
            UPDATE knowledge_collection_lifecycle
            SET state = CASE WHEN #{error} IS NULL AND create_acknowledged = 1
                             THEN 'RECLAIMED' ELSE 'RECLAIMING' END,
                next_cleanup_at = TIMESTAMPADD(SECOND, #{delay}, CURRENT_TIMESTAMP),
                cleanup_lease_owner = NULL, cleanup_lease_until = NULL, last_cleanup_error = #{error}
            WHERE collection_name = #{collection} AND state = 'RECLAIMING' AND cleanup_lease_owner = #{owner}
            """)
    int finish(@Param("collection") String collection, @Param("owner") String owner,
               @Param("delay") int delay, @Param("error") String error);
}
