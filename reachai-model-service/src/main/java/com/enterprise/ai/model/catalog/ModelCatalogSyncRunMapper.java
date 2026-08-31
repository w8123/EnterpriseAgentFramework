package com.enterprise.ai.model.catalog;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Mapper
public interface ModelCatalogSyncRunMapper extends BaseMapper<ModelCatalogSyncRunEntity> {

    @Insert("""
            INSERT IGNORE INTO model_catalog_sync_run
                (source_id, business_date, trigger_type, status, attempt_count, max_attempts,
                 available_at, analysis_status, candidate_count, published_count, review_count,
                 created_at, updated_at)
            SELECT id, #{businessDate}, #{triggerType}, 'PENDING', 0, #{maxAttempts},
                   NOW(), 'PENDING', 0, 0, 0, NOW(), NOW()
            FROM model_catalog_source
            WHERE enabled = 1
            """)
    int createDailySlots(@Param("businessDate") LocalDate businessDate,
                         @Param("triggerType") String triggerType,
                         @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE model_catalog_sync_run
            SET status = 'RETRY',
                trigger_type = 'STARTUP',
                attempt_count = 0,
                max_attempts = #{maxAttempts},
                available_at = NOW(),
                lease_owner = NULL,
                lease_token = NULL,
                leased_until = NULL,
                completed_at = NULL,
                updated_at = NOW()
            WHERE business_date = #{businessDate}
              AND status = 'DEAD'
            """)
    int requeueDeadSlotsOnStartup(@Param("businessDate") LocalDate businessDate,
                                  @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE model_catalog_sync_run
            SET trigger_type = 'MANUAL',
                attempt_count = CASE WHEN status = 'DEAD' THEN 0 ELSE attempt_count END,
                status = CASE WHEN status = 'DEAD' THEN 'RETRY' ELSE status END,
                max_attempts = #{maxAttempts},
                available_at = NOW(),
                lease_owner = NULL,
                lease_token = NULL,
                leased_until = NULL,
                last_error_code = NULL,
                last_error_message = NULL,
                completed_at = NULL,
                updated_at = NOW()
            WHERE business_date = #{businessDate}
              AND status IN ('PENDING', 'RETRY', 'DEAD')
            """)
    int prepareManualDailySlots(@Param("businessDate") LocalDate businessDate,
                                @Param("maxAttempts") int maxAttempts);

    @Select("""
            SELECT COUNT(*)
            FROM model_catalog_sync_run
            WHERE business_date = #{businessDate}
              AND status IN ('SUCCESS', 'NO_CHANGE')
            """)
    int countCompletedForDate(@Param("businessDate") LocalDate businessDate);

    @Select("""
            SELECT COUNT(*)
            FROM model_catalog_sync_run
            WHERE business_date = #{businessDate}
              AND status NOT IN ('SUCCESS', 'NO_CHANGE')
            """)
    int countIncompleteForDate(@Param("businessDate") LocalDate businessDate);

    @Select("""
            SELECT id
            FROM model_catalog_sync_run
            WHERE attempt_count < max_attempts
              AND (
                (status IN ('PENDING', 'RETRY') AND available_at <= NOW())
                OR (status IN ('LEASED', 'RUNNING') AND leased_until < NOW())
              )
            ORDER BY business_date ASC, available_at ASC, id ASC
            LIMIT 1
            """)
    Long findLeaseCandidateId();

    @Update("""
            UPDATE model_catalog_sync_run
            SET status = 'LEASED',
                lease_owner = #{leaseOwner},
                lease_token = #{leaseToken},
                leased_until = #{leasedUntil},
                attempt_count = attempt_count + 1,
                started_at = COALESCE(started_at, NOW()),
                completed_at = NULL,
                updated_at = NOW()
            WHERE id = #{runId}
              AND attempt_count < max_attempts
              AND (
                (status IN ('PENDING', 'RETRY') AND available_at <= NOW())
                OR (status IN ('LEASED', 'RUNNING') AND leased_until < NOW())
              )
            """)
    int claim(@Param("runId") Long runId,
              @Param("leaseOwner") String leaseOwner,
              @Param("leaseToken") String leaseToken,
              @Param("leasedUntil") LocalDateTime leasedUntil);

    @Update("""
            UPDATE model_catalog_sync_run
            SET status = 'RUNNING', updated_at = NOW()
            WHERE id = #{runId} AND status = 'LEASED' AND lease_token = #{leaseToken}
            """)
    int markRunning(@Param("runId") Long runId, @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE model_catalog_sync_run
            SET leased_until = #{leasedUntil}, updated_at = NOW()
            WHERE id = #{runId}
              AND status IN ('LEASED', 'RUNNING')
              AND lease_token = #{leaseToken}
            """)
    int renew(@Param("runId") Long runId,
              @Param("leaseToken") String leaseToken,
              @Param("leasedUntil") LocalDateTime leasedUntil);

    @Update("""
            UPDATE model_catalog_sync_run
            SET http_status = #{httpStatus},
                content_sha256 = #{contentSha256},
                snapshot_id = #{snapshotId},
                analysis_status = #{analysisStatus},
                updated_at = NOW()
            WHERE id = #{runId}
              AND status = 'RUNNING'
              AND lease_token = #{leaseToken}
            """)
    int recordSnapshot(@Param("runId") Long runId,
                       @Param("leaseToken") String leaseToken,
                       @Param("httpStatus") Integer httpStatus,
                       @Param("contentSha256") String contentSha256,
                       @Param("snapshotId") Long snapshotId,
                       @Param("analysisStatus") String analysisStatus);

    @Update("""
            UPDATE model_catalog_sync_run
            SET status = #{status},
                http_status = #{httpStatus},
                content_sha256 = #{contentSha256},
                snapshot_id = #{snapshotId},
                analysis_status = #{analysisStatus},
                candidate_count = #{candidateCount},
                published_count = #{publishedCount},
                review_count = #{reviewCount},
                lease_owner = NULL,
                lease_token = NULL,
                leased_until = NULL,
                last_error_code = NULL,
                last_error_message = NULL,
                completed_at = NOW(),
                updated_at = NOW()
            WHERE id = #{runId}
              AND status = 'RUNNING'
              AND lease_token = #{leaseToken}
            """)
    int complete(@Param("runId") Long runId,
                 @Param("leaseToken") String leaseToken,
                 @Param("status") String status,
                 @Param("httpStatus") Integer httpStatus,
                 @Param("contentSha256") String contentSha256,
                 @Param("snapshotId") Long snapshotId,
                 @Param("analysisStatus") String analysisStatus,
                 @Param("candidateCount") int candidateCount,
                 @Param("publishedCount") int publishedCount,
                 @Param("reviewCount") int reviewCount);

    @Update("""
            UPDATE model_catalog_sync_run
            SET status = #{nextStatus},
                available_at = #{availableAt},
                lease_owner = NULL,
                lease_token = NULL,
                leased_until = NULL,
                last_error_code = #{errorCode},
                last_error_message = #{errorMessage},
                completed_at = CASE WHEN #{nextStatus} = 'DEAD' THEN NOW() ELSE NULL END,
                updated_at = NOW()
            WHERE id = #{runId}
              AND status IN ('LEASED', 'RUNNING')
              AND lease_token = #{leaseToken}
            """)
    int release(@Param("runId") Long runId,
                @Param("leaseToken") String leaseToken,
                @Param("nextStatus") String nextStatus,
                @Param("availableAt") LocalDateTime availableAt,
                @Param("errorCode") String errorCode,
                @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE model_catalog_sync_run
            SET status = 'DEAD',
                lease_owner = NULL,
                lease_token = NULL,
                leased_until = NULL,
                last_error_code = 'MODEL_CATALOG_LEASE_EXHAUSTED',
                last_error_message = 'Catalog sync lease expired after the final allowed attempt',
                completed_at = NOW(),
                updated_at = NOW()
            WHERE status IN ('LEASED', 'RUNNING')
              AND leased_until < NOW()
              AND attempt_count >= max_attempts
            """)
    int markExpiredExhausted();

    @Select("""
            SELECT *
            FROM model_catalog_sync_run
            WHERE source_id = #{sourceId}
            ORDER BY business_date DESC, id DESC
            LIMIT 1
            """)
    ModelCatalogSyncRunEntity findLatestForSource(@Param("sourceId") Long sourceId);
}
