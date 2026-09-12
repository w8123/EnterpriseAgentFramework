package com.enterprise.ai.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ai.domain.entity.DocumentIndexExecution;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface DocumentIndexExecutionRepository extends BaseMapper<DocumentIndexExecution> {
    @Select("SELECT * FROM knowledge_document_index_execution WHERE lease_owner=#{lease} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    DocumentIndexExecution lock(@Param("lease") String lease);

    @Select("""
            SELECT e.* FROM knowledge_document_index_execution e
            LEFT JOIN knowledge_document_import_job j ON j.job_id=e.job_id
            WHERE e.next_cleanup_at <= CURRENT_TIMESTAMP
              AND (e.cleanup_lease_until IS NULL OR e.cleanup_lease_until <= CURRENT_TIMESTAMP)
              AND (e.state='RECLAIMING' OR (e.state='REGISTERED' AND
                ((e.operation_type='JOB' AND (j.id IS NULL OR j.status <> 'INDEXING' OR j.lease_owner IS NULL
                 OR j.lease_owner <> e.lease_owner OR j.lease_until IS NULL OR j.lease_until <= CURRENT_TIMESTAMP))
                 OR (e.operation_type<>'JOB' AND (e.publication_deadline IS NULL OR e.publication_deadline <= CURRENT_TIMESTAMP)))))
            ORDER BY e.next_cleanup_at, e.lease_owner LIMIT #{limit}
            """)
    List<DocumentIndexExecution> findReclaimable(@Param("limit") int limit);

    @Update("""
            UPDATE knowledge_document_index_execution
            SET publication_deadline=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP)
            WHERE lease_owner=#{lease} AND operation_type IN ('DIRECT','PIPELINE','REEMBED')
              AND state='REGISTERED' AND publication_deadline IS NULL
            """)
    int startPublicationDeadline(@Param("lease") String lease, @Param("seconds") int seconds);

    // Run after acquiring the execution lock so CURRENT_TIMESTAMP is fresh after any wait.
    @Select("""
            SELECT * FROM knowledge_document_index_execution WHERE lease_owner=#{lease}
              AND operation_type IN ('DIRECT','PIPELINE','REEMBED') AND state='REGISTERED'
              AND publication_deadline > CURRENT_TIMESTAMP FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    DocumentIndexExecution lockPublicationOpen(@Param("lease") String lease);

    @Update("""
            UPDATE knowledge_document_index_execution SET state='RECLAIMING', next_cleanup_at=CURRENT_TIMESTAMP
            WHERE lease_owner=#{lease} AND operation_type IN ('DIRECT','PIPELINE','REEMBED') AND state='REGISTERED'
            """)
    int abandonStandalone(@Param("lease") String lease);

    @Update("""
            UPDATE knowledge_document_index_execution SET state='RECLAIMING', cleanup_cursor=0,
              next_cleanup_at=CURRENT_TIMESTAMP, cleanup_lease_owner=NULL, cleanup_lease_until=NULL
            WHERE lease_owner=#{lease} AND state IN ('REGISTERED','PUBLISHED')
            """)
    int retireForFileDeletion(@Param("lease") String lease);

    @Update("""
            UPDATE knowledge_document_index_execution SET next_cleanup_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP)
            WHERE lease_owner=#{lease} AND operation_type='RETIRED_VECTOR' AND state='RECLAIMING'
              AND (cleanup_lease_until IS NULL OR cleanup_lease_until <= CURRENT_TIMESTAMP)
            """)
    int deferReferencedRetirement(@Param("lease") String lease);

    // A late ACK invalidates any sweep started before the write finished, including its cursor.
    @Update("""
            UPDATE knowledge_document_index_execution SET write_acknowledged=1, cleanup_cursor=0,
              next_cleanup_at=CURRENT_TIMESTAMP, cleanup_lease_owner=NULL, cleanup_lease_until=NULL
            WHERE lease_owner=#{lease} AND write_acknowledged=0 AND state IN ('REGISTERED','RECLAIMING')
            """)
    int acknowledge(@Param("lease") String lease);

    @Update("""
            UPDATE knowledge_document_index_execution SET state='PUBLISHED'
            WHERE lease_owner=#{lease} AND state='REGISTERED' AND write_acknowledged=1
              AND (operation_type='JOB' OR publication_deadline > CURRENT_TIMESTAMP)
            """)
    int publish(@Param("lease") String lease);

    @Update("""
            UPDATE knowledge_document_index_execution SET state='RECLAIMING', cleanup_lease_owner=#{owner},
              cleanup_lease_until=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP)
            WHERE lease_owner=#{lease} AND state IN ('REGISTERED','RECLAIMING')
              AND next_cleanup_at <= CURRENT_TIMESTAMP
              AND (cleanup_lease_until IS NULL OR cleanup_lease_until <= CURRENT_TIMESTAMP)
            """)
    int claim(@Param("lease") String lease, @Param("owner") String owner, @Param("seconds") int seconds);

    // Owner CAS is authoritative: an expired owner may finish only if no successor has claimed it.
    @Update("""
            UPDATE knowledge_document_index_execution SET cleanup_cursor=#{cursor},
              cleanup_lease_until=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP)
            WHERE lease_owner=#{lease} AND state='RECLAIMING' AND cleanup_lease_owner=#{owner}
              AND cleanup_cursor=#{previousCursor}
            """)
    int checkpoint(@Param("lease") String lease, @Param("owner") String owner,
                   @Param("previousCursor") int previousCursor, @Param("cursor") int cursor);

    @Update("""
            UPDATE knowledge_document_index_execution SET cleanup_cursor=#{cursor}, state=#{state},
              next_cleanup_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP),
              cleanup_lease_owner=NULL, cleanup_lease_until=NULL, last_cleanup_error=#{error}
            WHERE lease_owner=#{lease} AND state='RECLAIMING' AND cleanup_lease_owner=#{owner}
              AND cleanup_cursor=#{previousCursor}
            """)
    int progress(@Param("lease") String lease, @Param("owner") String owner,
                 @Param("previousCursor") int previousCursor, @Param("cursor") int cursor,
                 @Param("state") String state, @Param("delay") int delay, @Param("error") String error);
}
