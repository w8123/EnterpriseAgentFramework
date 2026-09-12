package com.enterprise.ai.service.impl;

import com.enterprise.ai.repository.DocumentImportJobRepository;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DocumentImportLeaseRecoveryTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 3, 4})
    void expiredParsingRespectsAttemptBudgetAndLeavesOtherLeasesAlone(int attempts) throws Exception {
        try (var db = new KnowledgeQueryTestDatabase(List.of("knowledge_document_import_job"), DocumentImportJobRepository.class)) {
            for (String id : List.of("expired", "live", "index")) {
                db.jdbc().update("INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,file_name,file_type,source_object_key,provider_type,status,stage,attempt_count,max_attempts,lease_owner,lease_until) VALUES (?,?,7,'kb','a.pdf','pdf','source','DOCLING',?,'PARSING',?,3,'worker',?)",
                        id, id, id.equals("index") ? "INDEXING" : "PARSING", attempts,
                        java.sql.Timestamp.valueOf(id.equals("live") ? "2099-01-01 00:00:00" : "2000-01-01 00:00:00"));
            }
            var jobs = db.mapper(DocumentImportJobRepository.class);
            assertEquals(1, jobs.recoverExpiredParsingLeases());
            var row = db.jdbc().queryForMap("SELECT * FROM knowledge_document_import_job WHERE job_id='expired'");
            assertEquals(attempts < 3 ? "RETRY_WAIT" : "FAILED", row.get("status"));
            assertEquals("PARSING", row.get("stage"));
            assertNull(row.get("lease_owner"));
            assertNull(row.get("lease_until"));
            if (attempts < 3) assertNotNull(row.get("next_attempt_at"));
            else assertNull(row.get("next_attempt_at"));
            assertEquals(attempts < 3 ? List.of("expired") : List.of(), jobs.findPendingParseJobIds(10));
            assertEquals("PARSING", db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job WHERE job_id='live'", String.class));
            assertEquals("INDEXING", db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job WHERE job_id='index'", String.class));
            assertEquals(0, jobs.recoverExpiredParsingLeases());
        }
    }
}
