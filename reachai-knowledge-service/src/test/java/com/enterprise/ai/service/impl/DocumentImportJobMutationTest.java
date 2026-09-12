package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.DocumentImportAccessContext;
import com.enterprise.ai.pipeline.document.DocumentParseRouter;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.pipeline.document.job.*;
import com.enterprise.ai.pipeline.step.*;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class DocumentImportJobMutationTest {
    private static final DocumentImportAccessContext ACCESS = new DocumentImportAccessContext("default", "42", null, null, null);

    @ParameterizedTest
    @ValueSource(strings = {"commit", "retry", "cancel"})
    void mutationHoldsTheJobLockBeforeCheckingAndChangingState(String operation) throws Exception {
        try (var db = database()) {
            seed(db, operation.equals("retry") ? "RETRY_WAIT" : "PARSED");
            db.jdbc().execute("SET DEFAULT_LOCK_TIMEOUT 200");
            var real = db.mapper(DocumentImportJobRepository.class);
            var jobs = mock(DocumentImportJobRepository.class, delegatesTo(real));
            var executor = Executors.newSingleThreadExecutor();
            try {
                doAnswer(call -> {
                    var result = real.selectOne(call.getArgument(0));
                    var competitor = executor.submit(() -> db.jdbc().update(
                            "UPDATE knowledge_document_import_job SET status='INDEXING' WHERE job_id='job'"));
                    var failure = assertThrows(ExecutionException.class, () -> competitor.get(5, TimeUnit.SECONDS));
                    var timeout = assertInstanceOf(org.springframework.dao.QueryTimeoutException.class, failure.getCause());
                    var sqlFailure = assertInstanceOf(java.sql.SQLException.class, timeout.getCause());
                    assertEquals(50200, sqlFailure.getErrorCode()); // H2 row lock timeout
                    return result;
                }).when(jobs).selectOne(any());
                var service = service(jobs);
                new TransactionTemplate(new DataSourceTransactionManager(db.jdbc().getDataSource()))
                        .executeWithoutResult(status -> invoke(service, operation));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"retry", "cancel"})
    void clearsPersistedLeaseAndRetryMetadata(String operation) throws Exception {
        try (var db = database()) {
            seed(db, "RETRY_WAIT");
            var service = service(db.mapper(DocumentImportJobRepository.class));
            new TransactionTemplate(new DataSourceTransactionManager(db.jdbc().getDataSource()))
                    .executeWithoutResult(status -> invoke(service, operation));
            var row = db.jdbc().queryForMap("SELECT * FROM knowledge_document_import_job WHERE job_id='job'");
            assertNull(row.get("lease_owner"));
            assertNull(row.get("lease_until"));
            if (operation.equals("retry")) {
                assertNull(row.get("next_attempt_at"));
                assertNull(row.get("error_code"));
                assertNull(row.get("error_message"));
                assertEquals(0, ((Number) row.get("attempt_count")).intValue());
            }
        }
    }

    private static org.mockito.stubbing.Answer<Object> delegatesTo(Object delegate) {
        return org.mockito.AdditionalAnswers.delegatesTo(delegate);
    }

    private static KnowledgeQueryTestDatabase database() throws Exception {
        return new KnowledgeQueryTestDatabase(List.of("knowledge_document_import_job"), DocumentImportJobRepository.class);
    }

    private static void seed(KnowledgeQueryTestDatabase db, String status) {
        db.jdbc().update("INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,created_by_actor_id,file_name,file_type,source_object_key,provider_type,status,stage,attempt_count,lease_owner,lease_until,next_attempt_at,error_code,error_message,vector_collection_name) VALUES ('job','file',7,'kb','42','a.pdf','pdf','source','DOCLING',?,'PARSING',2,'old-worker',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'OLD','old failure','kb')", status);
    }

    private static DocumentImportJobServiceImpl service(DocumentImportJobRepository jobs) {
        var properties = new DocumentImportJobProperties();
        properties.setEnabled(false);
        var bases = mock(KnowledgeBaseRepository.class);
        var kb = new com.enterprise.ai.domain.entity.KnowledgeBase();
        kb.setId(7L); kb.setCode("kb"); kb.setVectorCollectionName("kb");
        when(bases.selectById(7L)).thenReturn(kb);
        return new DocumentImportJobServiceImpl(jobs, bases, mock(FileInfoRepository.class), mock(DocumentArtifactStore.class), mock(DocumentParseRouter.class), new ObjectMapper(), properties, mock(DocumentImportJobWorker.class), mock(TextCleanStep.class), mock(ChunkStep.class), Runnable::run, com.enterprise.ai.support.ArtifactLifecycleTestSupport.transactions());
    }

    private static void invoke(DocumentImportJobServiceImpl service, String operation) {
        switch (operation) {
            case "commit" -> service.commit("job", ACCESS);
            case "retry" -> service.retry("job", ACCESS);
            case "cancel" -> service.cancel("job", ACCESS);
            default -> throw new IllegalArgumentException(operation);
        }
    }
}
