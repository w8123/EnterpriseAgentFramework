package com.enterprise.ai.runtime.managed;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ManagedExecutionOutboxMapper extends BaseMapper<ManagedExecutionOutboxEntity> {

    @Select("""
            SELECT id
            FROM runtime_managed_execution_outbox
            WHERE (
                    status IN ('PENDING', 'FAILED')
                    AND available_at <= #{now}
                  )
               OR (
                    status = 'PUBLISHING'
                    AND lease_expires_at <= #{now}
                  )
            ORDER BY available_at ASC, id ASC
            LIMIT 1
            """)
    Long findPublishCandidateId(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE runtime_managed_execution_outbox
            SET status = 'PUBLISHING',
                attempt_count = attempt_count + 1,
                lease_owner = #{leaseOwner},
                lease_token = #{leaseToken},
                lease_expires_at = #{leaseExpiresAt},
                last_error = NULL,
                updated_at = #{now}
            WHERE id = #{id}
              AND (
                    (status IN ('PENDING', 'FAILED') AND available_at <= #{now})
                    OR (status = 'PUBLISHING' AND lease_expires_at <= #{now})
                  )
            """)
    int claimForPublish(@Param("id") Long id,
                        @Param("leaseOwner") String leaseOwner,
                        @Param("leaseToken") String leaseToken,
                        @Param("now") LocalDateTime now,
                        @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Update("""
            UPDATE runtime_managed_execution_outbox
            SET status = 'PUBLISHED',
                lease_owner = NULL,
                lease_token = NULL,
                lease_expires_at = NULL,
                last_error = NULL,
                published_at = #{now},
                updated_at = #{now}
            WHERE id = #{id}
              AND status = 'PUBLISHING'
              AND lease_token = #{leaseToken}
            """)
    int markPublished(@Param("id") Long id,
                      @Param("leaseToken") String leaseToken,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE runtime_managed_execution_outbox
            SET status = 'FAILED',
                available_at = #{availableAt},
                lease_owner = NULL,
                lease_token = NULL,
                lease_expires_at = NULL,
                last_error = #{lastError},
                updated_at = #{now}
            WHERE id = #{id}
              AND status = 'PUBLISHING'
              AND lease_token = #{leaseToken}
            """)
    int releaseAfterFailure(@Param("id") Long id,
                            @Param("leaseToken") String leaseToken,
                            @Param("lastError") String lastError,
                            @Param("availableAt") LocalDateTime availableAt,
                            @Param("now") LocalDateTime now);
}
