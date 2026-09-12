package com.enterprise.ai.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ai.domain.entity.DocumentImportJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** Atomic claims stop two knowledge-service replicas from parsing the same source. */
@Mapper
public interface DocumentImportJobRepository extends BaseMapper<DocumentImportJob> {

    @Select("""
            SELECT * FROM knowledge_document_import_job
            WHERE job_id=#{jobId} AND status='PARSING' AND lease_owner=#{leaseOwner}
              AND lease_until > CURRENT_TIMESTAMP FOR UPDATE
            """)
    @org.apache.ibatis.annotations.Options(useCache = false,
            flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    DocumentImportJob lockValidParsing(@Param("jobId") String jobId, @Param("leaseOwner") String leaseOwner);

    /** Called after acquiring the job lock so CURRENT_TIMESTAMP cannot precede a lock wait. */
    @Select("""
            SELECT * FROM knowledge_document_import_job
            WHERE job_id=#{jobId} AND status='INDEXING' AND lease_owner=#{leaseOwner}
              AND lease_until > CURRENT_TIMESTAMP FOR UPDATE
            """)
    @org.apache.ibatis.annotations.Options(useCache = false,
            flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    DocumentImportJob lockValidIndexing(@Param("jobId") String jobId, @Param("leaseOwner") String leaseOwner);

    @Update("""
            UPDATE knowledge_document_import_job
               SET status = 'PARSING', stage = 'PARSING', attempt_count = attempt_count + 1,
                   lease_owner = #{leaseOwner}, lease_until = DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND),
                   error_code = NULL, error_message = NULL
             WHERE job_id = #{jobId}
               AND status IN ('QUEUED', 'RETRY_WAIT')
               AND (next_attempt_at IS NULL OR next_attempt_at <= NOW())
            """)
    int claimForParsing(@Param("jobId") String jobId,
                        @Param("leaseOwner") String leaseOwner,
                        @Param("leaseSeconds") int leaseSeconds);

    @Update("""
            UPDATE knowledge_document_import_job
               SET status = 'INDEXING', stage = 'INDEXING',
                   lease_owner = #{leaseOwner}, lease_until = DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND),
                   error_code = NULL, error_message = NULL
             WHERE job_id = #{jobId}
               AND status = 'PARSED'
            """)
    int claimForIndexing(@Param("jobId") String jobId,
                         @Param("leaseOwner") String leaseOwner,
                         @Param("leaseSeconds") int leaseSeconds);

    @Update("""
            UPDATE knowledge_document_import_job
               SET status = 'PARSED', stage = 'PARSED',
                   provider_type = #{providerType}, provider_version = #{providerVersion},
                   parse_artifact_object_key = #{artifactObjectKey}, parsed_at = #{parsedAt},
                   lease_owner = NULL, lease_until = NULL, next_attempt_at = NULL,
                   error_code = NULL, error_message = NULL
             WHERE job_id = #{jobId}
               AND status = 'PARSING'
               AND lease_owner = #{leaseOwner}
               AND lease_until > CURRENT_TIMESTAMP
            """)
    int finalizeParsing(@Param("jobId") String jobId,
                        @Param("leaseOwner") String leaseOwner,
                        @Param("providerType") String providerType,
                        @Param("providerVersion") String providerVersion,
                        @Param("artifactObjectKey") String artifactObjectKey,
                        @Param("parsedAt") LocalDateTime parsedAt);

    @Update("""
            UPDATE knowledge_document_import_job
               SET status = #{status}, stage = 'PARSING', next_attempt_at = #{nextAttemptAt},
                   lease_owner = NULL, lease_until = NULL,
                   error_code = #{errorCode}, error_message = #{errorMessage}
             WHERE job_id = #{jobId}
               AND status = 'PARSING'
               AND lease_owner = #{leaseOwner}
            """)
    int failParsing(@Param("jobId") String jobId,
                    @Param("leaseOwner") String leaseOwner,
                    @Param("status") String status,
                    @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE knowledge_document_import_job
               SET lease_until = DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND)
             WHERE job_id = #{jobId}
               AND status = 'INDEXING'
               AND lease_owner = #{leaseOwner}
            """)
    int renewIndexingLease(@Param("jobId") String jobId,
                           @Param("leaseOwner") String leaseOwner,
                           @Param("leaseSeconds") int leaseSeconds);

    @Update("""
            UPDATE knowledge_document_import_job
               SET status = 'COMPLETED', stage = 'COMPLETED', completed_at = #{completedAt},
                   lease_owner = NULL, lease_until = NULL,
                   error_code = NULL, error_message = NULL
             WHERE job_id = #{jobId}
               AND status = 'INDEXING'
               AND lease_owner = #{leaseOwner}
            """)
    int finalizeIndexing(@Param("jobId") String jobId,
                         @Param("leaseOwner") String leaseOwner,
                         @Param("completedAt") LocalDateTime completedAt);

    @Update("""
            UPDATE knowledge_document_import_job
               SET status = 'FAILED', stage = 'INDEXING',
                   lease_owner = NULL, lease_until = NULL,
                   error_code = 'INDEXING_FAILED', error_message = #{errorMessage}
             WHERE job_id = #{jobId}
               AND status = 'INDEXING'
               AND lease_owner = #{leaseOwner}
            """)
    int failIndexing(@Param("jobId") String jobId,
                     @Param("leaseOwner") String leaseOwner,
                     @Param("errorMessage") String errorMessage);

    @Select("""
            SELECT job_id FROM knowledge_document_import_job
             WHERE status IN ('QUEUED', 'RETRY_WAIT')
               AND (next_attempt_at IS NULL OR next_attempt_at <= NOW())
             ORDER BY create_time ASC
             LIMIT #{limit}
            """)
    List<String> findPendingParseJobIds(@Param("limit") int limit);

    @Select("""
            SELECT job_id FROM knowledge_document_import_job
             WHERE status = 'PARSED' AND auto_commit = 1
             ORDER BY parsed_at ASC
             LIMIT #{limit}
            """)
    List<String> findPendingAutoCommitJobIds(@Param("limit") int limit);

    @Update("""
            UPDATE knowledge_document_import_job
               SET status = CASE WHEN attempt_count < max_attempts THEN 'RETRY_WAIT' ELSE 'FAILED' END,
                   stage = 'PARSING',
                   next_attempt_at = CASE WHEN attempt_count < max_attempts THEN NOW() ELSE NULL END,
                   lease_owner = NULL, lease_until = NULL,
                   error_code = 'WORKER_LEASE_EXPIRED',
                   error_message = CASE WHEN attempt_count < max_attempts
                       THEN '解析 worker 租约过期，已安排同一 Provider 重试'
                       ELSE '解析 worker 租约过期，已达到最大尝试次数；请手动重试' END
             WHERE status = 'PARSING' AND lease_until IS NOT NULL AND lease_until < NOW()
            """)
    int recoverExpiredParsingLeases();

    @Update("""
            UPDATE knowledge_document_import_job
               SET status = 'FAILED', stage = 'INDEXING', lease_owner = NULL, lease_until = NULL,
                   error_code = 'INDEXING_LEASE_EXPIRED',
                   error_message = '索引 worker 租约过期；请从已保存解析结果手动重试'
             WHERE status = 'INDEXING' AND lease_until IS NOT NULL AND lease_until < NOW()
            """)
    int failExpiredIndexingLeases();
}
