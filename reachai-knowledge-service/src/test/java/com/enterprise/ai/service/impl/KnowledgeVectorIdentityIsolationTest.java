package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.KnowledgeImportRequest;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.pipeline.document.job.DocumentIndexVectorManifest;
import com.enterprise.ai.pipeline.step.VectorStoreStep;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.retrieval.KnowledgeRetrievalEngine;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.enterprise.ai.vector.VectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeVectorIdentityIsolationTest {
    private KnowledgeQueryTestDatabase db;
    private KnowledgeServiceImpl service;
    private VectorService vectors;
    private TransactionTemplate transactions;
    private final List<List<String>> writes = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        db = new KnowledgeQueryTestDatabase(List.of("knowledge_base", "knowledge_file_info", "knowledge_chunk", "knowledge_document_import_job", "knowledge_document_index_execution"),
                KnowledgeBaseRepository.class, FileInfoRepository.class, ChunkRepository.class, DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class);
        db.jdbc().update("INSERT INTO knowledge_base(id,name,code,vector_collection_name,dimension,embedding_model_instance_id) VALUES (7,'测试知识库','kb','kb',2,'fixture-model')");
        var bases = db.mapper(KnowledgeBaseRepository.class);
        var embedding = mock(EmbeddingService.class);
        when(embedding.embedBatch(anyString(), anyList())).thenAnswer(invocation ->
                Collections.nCopies(((List<?>) invocation.getArgument(1)).size(), List.of(1.0f, 0.0f)));
        when(embedding.embed(anyString(), anyString())).thenReturn(List.of(1.0f, 0.0f));
        vectors = mock(VectorService.class);
        doAnswer(invocation -> {
            writes.add(List.copyOf(invocation.<List<String>>getArgument(1)));
            return null;
        }).when(vectors).upsert(anyString(), anyList(), anyList(), anyList(), anyList());
        service = KnowledgeManagementTestServices.create(db.mapper(FileInfoRepository.class), db.mapper(ChunkRepository.class), mock(KnowledgeTagRepository.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class), mock(KnowledgeOperationsQuery.class), KnowledgeIndexTestSupport.writer(db, embedding, vectors), new KnowledgeBaseLookup(bases), mock(KnowledgeRetrievalEngine.class), KnowledgeIndexTestSupport.deletion(db), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeBaseLifecycleService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(db.jdbc().getDataSource()));
    }

    @AfterEach
    void close() {
        if (db != null) db.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"manifest-prefix", "manifest-prefix-with-chunk", "normal", "maximum-length"})
    void callerFileIdsCannotEnterExecutionNamespaceThroughEitherImportEntry(String mode) {
        var execution = DocumentIndexVectorManifest.forExecution("owned-file", "owned-lease", 2);
        String fileId = switch (mode) {
            case "manifest-prefix" -> execution.prefix();
            case "manifest-prefix-with-chunk" -> execution.prefix() + "_chunk";
            case "maximum-length" -> "f".repeat(128);
            default -> "正常文件-1";
        };
        var request = new KnowledgeImportRequest();
        request.setKnowledgeBaseCode("kb"); request.setFileId(fileId); request.setFileName("测试文档.txt");
        request.setChunks(List.of("第一段", "第二段"));
        for (int attempt = 0; attempt < 2; attempt++) {
            service.importChunks(request);
            assertEquals(writes.get(writes.size() - 1), db.jdbc().queryForList(
                    "SELECT vector_id FROM knowledge_chunk ORDER BY chunk_index", String.class));
            db.jdbc().update("DELETE FROM knowledge_chunk");
            db.jdbc().update("DELETE FROM knowledge_file_info");
        }
        List<String> directIds = writes.get(0);
        assertTrue(Collections.disjoint(directIds, writes.get(1)));
        assertNamespace(directIds, execution);

        var executions = KnowledgeIndexTestSupport.executions(db);
        var step = new VectorStoreStep(vectors, executions);
        var context = new PipelineContext();
        context.setKnowledgeBaseCode("kb"); context.setKnowledgeBaseId(7L); context.setVectorCollectionName("kb"); context.setFileId(fileId); context.setChunks(request.getChunks());
        context.setVectors(List.of(List.of(1.0f, 0.0f), List.of(0.0f, 1.0f)));
        context.setIndexOperation("PIPELINE"); context.setIndexExecutionId(java.util.UUID.randomUUID().toString());
        step.process(context);
        List<String> pipelineIds = List.copyOf(context.getVectorIds());
        assertThrows(com.enterprise.ai.pipeline.PipelineException.class, () -> step.process(context));
        context.setIndexExecutionId(java.util.UUID.randomUUID().toString());
        step.process(context);
        assertTrue(Collections.disjoint(pipelineIds, context.getVectorIds()));
        assertEquals(pipelineIds, writes.get(2));
        assertEquals(context.getVectorIds(), writes.get(3));
        assertNamespace(pipelineIds, execution);
        assertTrue(Collections.disjoint(directIds, pipelineIds));
        assertEquals(4, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution WHERE write_acknowledged=1", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "blank", "existing"})
    void reembeddingAlwaysUsesAFreshExecutionIdentityAndRetainsAnExactOldTarget(String mode) {
        var execution = DocumentIndexVectorManifest.forExecution("owned-file", "owned-lease", 1);
        String existing = switch (mode) {
            case "existing" -> execution.vectorId(0);
            case "blank" -> " ";
            default -> null;
        };
        db.jdbc().update("INSERT INTO knowledge_file_info(file_id,knowledge_base_id,file_name,record_generation) VALUES (?,7,'原始文件.txt',REPEAT('a',32))", execution.prefix());
        db.jdbc().update("INSERT INTO knowledge_chunk(id,file_id,knowledge_base_id,content,chunk_index,vector_id,collection_name) VALUES (1,?,7,'重新生成正文',0,?,'kb')",
                execution.prefix(), existing);
        transactions.executeWithoutResult(status -> service.reembedChunk(1L));
        List<String> ids = writes.get(0);
        assertNamespace(ids, execution);
        assertEquals(ids.get(0), db.jdbc().queryForObject("SELECT vector_id FROM knowledge_chunk WHERE id=1", String.class));
        verify(vectors, never()).deleteById(anyString(), anyString());
        if (mode.equals("existing")) assertEquals(existing, db.jdbc().queryForObject(
                "SELECT single_vector_id FROM knowledge_document_index_execution WHERE operation_type='RETIRED_VECTOR'", String.class));
    }

    private static void assertNamespace(List<String> ids, DocumentIndexVectorManifest execution) {
        assertTrue(Collections.disjoint(ids, execution.batch(0, execution.count())));
        for (int i = 0; i < ids.size(); i++) {
            assertTrue(ids.get(i).matches("[0-9a-f]{64}_chunk_" + i));
            assertTrue(ids.get(i).length() <= 128);
        }
    }
}
