package com.enterprise.ai.personalmemory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface KnowledgePersonalMemoryIndexMapper extends BaseMapper<KnowledgePersonalMemoryIndexEntity> {

    /**
     * Atomically applies a projection only when the stored source version is older.
     * This is the final ordering guard when multiple Control/Knowledge replicas
     * deliver different outbox events concurrently.
     */
    @Update("""
            UPDATE knowledge_personal_memory_index
               SET item_type = #{item.itemType},
                   title = #{item.title},
                   content = #{item.content},
                   summary = #{item.summary},
                   trust_level = #{item.trustLevel},
                   source_version = #{item.sourceVersion},
                   status = #{item.status},
                   expires_at = #{item.expiresAt},
                   embedding_vector = #{item.embeddingVector},
                   embedding_format = #{item.embeddingFormat},
                   embedding_dimension = #{item.embeddingDimension},
                   embedding_model_instance_id = #{item.embeddingModelInstanceId},
                   embedding_source_version = #{item.embeddingSourceVersion},
                   embedding_text_sha256 = #{item.embeddingTextSha256},
                   embedding_status = #{item.embeddingStatus},
                   embedding_attempts = #{item.embeddingAttempts},
                   embedding_error_code = #{item.embeddingErrorCode},
                   embedding_next_attempt_at = #{item.embeddingNextAttemptAt},
                   embedding_claim_token = CASE
                       WHEN #{item.status} = 'DELETED' THEN NULL
                       ELSE embedding_claim_token
                   END,
                   embedding_claim_until = CASE
                       WHEN #{item.status} = 'DELETED' THEN NULL
                       ELSE embedding_claim_until
                   END,
                   updated_at = #{item.updatedAt},
                   deleted_at = #{item.deletedAt}
             WHERE tenant_id = #{item.tenantId}
               AND runtime_user_hash = #{item.runtimeUserHash}
               AND memory_id = #{item.memoryId}
               AND source_version < #{item.sourceVersion}
            """)
    int updateIfNewer(@Param("item") KnowledgePersonalMemoryIndexEntity item);

    @Select("""
            SELECT *
              FROM knowledge_personal_memory_index
             WHERE status = 'ACTIVE'
               AND (expires_at IS NULL OR expires_at > #{now})
               AND (embedding_next_attempt_at IS NULL OR embedding_next_attempt_at <= #{now})
               AND (embedding_claim_until IS NULL OR embedding_claim_until < #{now})
               AND (
                    embedding_status IN ('PENDING', 'RETRY', 'DISABLED')
                    OR (embedding_status = 'PROCESSING'
                        AND (embedding_claim_until IS NULL OR embedding_claim_until < #{now}))
                    OR (embedding_status = 'READY' AND (
                        embedding_vector IS NULL
                        OR embedding_dimension IS NULL
                        OR embedding_dimension <= 0
                        OR embedding_text_sha256 IS NULL
                        OR embedding_model_instance_id IS NULL
                        OR embedding_model_instance_id <> #{modelInstanceId}
                        OR embedding_source_version IS NULL
                        OR embedding_source_version <> source_version
                        OR embedding_format IS NULL
                        OR embedding_format <> #{format}
                    ))
                    OR (embedding_status = 'DEAD' AND (
                        embedding_model_instance_id IS NULL
                        OR embedding_model_instance_id <> #{modelInstanceId}
                        OR embedding_source_version IS NULL
                        OR embedding_source_version <> source_version
                        OR embedding_format IS NULL
                        OR embedding_format <> #{format}
                    ))
               )
             ORDER BY COALESCE(embedding_next_attempt_at, created_at), id
             LIMIT #{limit}
            """)
    List<KnowledgePersonalMemoryIndexEntity> selectEmbeddingCandidates(
            @Param("now") LocalDateTime now,
            @Param("modelInstanceId") String modelInstanceId,
            @Param("format") String format,
            @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) AS active_count,
                   COALESCE(SUM(CASE
                       WHEN embedding_status = 'READY'
                        AND embedding_model_instance_id = #{modelInstanceId}
                        AND embedding_source_version = source_version
                        AND embedding_format = #{format}
                        AND embedding_dimension > 0
                        AND embedding_text_sha256 IS NOT NULL
                        AND embedding_vector IS NOT NULL
                       THEN 1 ELSE 0 END), 0) AS ready_count,
                   COALESCE(SUM(CASE WHEN embedding_status = 'DEAD' THEN 1 ELSE 0 END), 0) AS dead_count,
                   COALESCE(MAX(CASE
                       WHEN embedding_status IN ('DISABLED', 'PENDING', 'PROCESSING', 'RETRY')
                       THEN GREATEST(TIMESTAMPDIFF(SECOND,
                           COALESCE(embedding_next_attempt_at, updated_at), #{now}), 0)
                       ELSE 0 END), 0) AS oldest_pending_seconds,
                   (SELECT COUNT(*)
                      FROM knowledge_personal_memory_index deleted_projection
                     WHERE deleted_projection.status <> 'ACTIVE'
                       AND (deleted_projection.embedding_vector IS NOT NULL
                            OR deleted_projection.embedding_format IS NOT NULL
                            OR deleted_projection.embedding_dimension IS NOT NULL
                            OR deleted_projection.embedding_model_instance_id IS NOT NULL
                            OR deleted_projection.embedding_source_version IS NOT NULL
                            OR deleted_projection.embedding_text_sha256 IS NOT NULL
                            OR deleted_projection.embedding_error_code IS NOT NULL
                            OR deleted_projection.embedding_next_attempt_at IS NOT NULL
                            OR deleted_projection.embedding_claim_token IS NOT NULL
                            OR deleted_projection.embedding_claim_until IS NOT NULL))
                       AS unsafe_deleted_vector_count
              FROM knowledge_personal_memory_index
             WHERE status = 'ACTIVE'
               AND (expires_at IS NULL OR expires_at > #{now})
            """)
    PersonalMemoryEmbeddingStatusRow selectEmbeddingStatus(
            @Param("now") LocalDateTime now,
            @Param("modelInstanceId") String modelInstanceId,
            @Param("format") String format);

    @Select("""
            SELECT COUNT(*) AS total_count,
                   COALESCE(SUM(CASE WHEN status = 'ACTIVE' THEN 1 ELSE 0 END), 0)
                       AS active_count,
                   COALESCE(SUM(CASE WHEN status <> 'ACTIVE' THEN 1 ELSE 0 END), 0)
                       AS deleted_count,
                   COALESCE(SUM(CASE WHEN status <> 'ACTIVE'
                       AND (embedding_vector IS NOT NULL
                            OR embedding_format IS NOT NULL
                            OR embedding_dimension IS NOT NULL
                            OR embedding_model_instance_id IS NOT NULL
                            OR embedding_source_version IS NOT NULL
                            OR embedding_text_sha256 IS NOT NULL
                            OR embedding_error_code IS NOT NULL
                            OR embedding_next_attempt_at IS NOT NULL
                            OR embedding_claim_token IS NOT NULL
                            OR embedding_claim_until IS NOT NULL)
                       THEN 1 ELSE 0 END), 0) AS unsafe_deleted_vector_count,
                   MAX(source_version) AS max_source_version,
                   MAX(updated_at) AS latest_updated_at
              FROM knowledge_personal_memory_index
             WHERE tenant_id = #{tenantId}
               AND runtime_user_hash = #{runtimeUserHash}
            """)
    PersonalMemoryOwnerProjectionStatusRow selectOwnerProjectionStatus(
            @Param("tenantId") String tenantId,
            @Param("runtimeUserHash") String runtimeUserHash);

    @Update("""
            UPDATE knowledge_personal_memory_index
               SET embedding_status = 'PROCESSING',
                   embedding_attempts = #{attempt},
                   embedding_error_code = NULL,
                   embedding_claim_token = #{claimToken},
                   embedding_claim_until = #{claimUntil}
             WHERE id = #{id}
               AND source_version = #{sourceVersion}
               AND status = 'ACTIVE'
               AND (embedding_next_attempt_at IS NULL OR embedding_next_attempt_at <= #{now})
               AND (embedding_claim_until IS NULL OR embedding_claim_until < #{now})
               AND (
                    embedding_status IN ('PENDING', 'RETRY', 'DISABLED')
                    OR (embedding_status = 'PROCESSING'
                        AND (embedding_claim_until IS NULL OR embedding_claim_until < #{now}))
                    OR (embedding_status = 'READY' AND (
                        embedding_vector IS NULL
                        OR embedding_dimension IS NULL
                        OR embedding_dimension <= 0
                        OR embedding_text_sha256 IS NULL
                        OR embedding_model_instance_id IS NULL
                        OR embedding_model_instance_id <> #{modelInstanceId}
                        OR embedding_source_version IS NULL
                        OR embedding_source_version <> source_version
                        OR embedding_format IS NULL
                        OR embedding_format <> #{format}
                    ))
                    OR (embedding_status = 'DEAD' AND (
                        embedding_model_instance_id IS NULL
                        OR embedding_model_instance_id <> #{modelInstanceId}
                        OR embedding_source_version IS NULL
                        OR embedding_source_version <> source_version
                        OR embedding_format IS NULL
                        OR embedding_format <> #{format}
                    ))
               )
            """)
    int claimEmbedding(@Param("id") Long id,
                       @Param("sourceVersion") Long sourceVersion,
                       @Param("claimToken") String claimToken,
                       @Param("attempt") int attempt,
                       @Param("now") LocalDateTime now,
                       @Param("claimUntil") LocalDateTime claimUntil,
                       @Param("modelInstanceId") String modelInstanceId,
                       @Param("format") String format);

    @Update("""
            UPDATE knowledge_personal_memory_index
               SET embedding_vector = #{vector},
                   embedding_format = #{format},
                   embedding_dimension = #{dimension},
                   embedding_model_instance_id = #{modelInstanceId},
                   embedding_source_version = #{sourceVersion},
                   embedding_text_sha256 = #{textSha256},
                   embedding_status = 'READY',
                   embedding_error_code = NULL,
                   embedding_next_attempt_at = NULL,
                   embedding_claim_token = NULL,
                   embedding_claim_until = NULL,
                   updated_at = #{now}
             WHERE id = #{id}
               AND source_version = #{sourceVersion}
               AND status = 'ACTIVE'
               AND embedding_claim_token = #{claimToken}
            """)
    int completeEmbedding(@Param("id") Long id,
                          @Param("sourceVersion") Long sourceVersion,
                          @Param("claimToken") String claimToken,
                          @Param("vector") byte[] vector,
                          @Param("format") String format,
                          @Param("dimension") Integer dimension,
                          @Param("modelInstanceId") String modelInstanceId,
                          @Param("textSha256") String textSha256,
                          @Param("now") LocalDateTime now);

    @Update("""
            UPDATE knowledge_personal_memory_index
               SET embedding_status = #{targetStatus},
                   embedding_vector = NULL,
                   embedding_format = #{format},
                   embedding_dimension = NULL,
                   embedding_model_instance_id = #{modelInstanceId},
                   embedding_source_version = #{sourceVersion},
                   embedding_text_sha256 = NULL,
                   embedding_error_code = #{errorCode},
                   embedding_next_attempt_at = #{nextAttemptAt},
                   embedding_claim_token = NULL,
                   embedding_claim_until = NULL,
                   updated_at = #{now}
             WHERE id = #{id}
               AND source_version = #{sourceVersion}
               AND status = 'ACTIVE'
               AND embedding_claim_token = #{claimToken}
            """)
    int failEmbedding(@Param("id") Long id,
                      @Param("sourceVersion") Long sourceVersion,
                      @Param("claimToken") String claimToken,
                      @Param("targetStatus") String targetStatus,
                      @Param("modelInstanceId") String modelInstanceId,
                      @Param("format") String format,
                      @Param("errorCode") String errorCode,
                      @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE knowledge_personal_memory_index
               SET embedding_claim_token = NULL,
                   embedding_claim_until = NULL
             WHERE id = #{id}
               AND embedding_claim_token = #{claimToken}
            """)
    int releaseEmbeddingClaim(@Param("id") Long id, @Param("claimToken") String claimToken);
}
