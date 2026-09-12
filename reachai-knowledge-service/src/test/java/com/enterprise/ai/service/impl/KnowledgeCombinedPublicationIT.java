package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.document.job.DocumentImportPublicationGuard;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.pipeline.document.job.DocumentIndexVectorManifest;
import com.enterprise.ai.pipeline.step.*;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.retrieval.*;
import com.enterprise.ai.vector.*;
import com.enterprise.ai.vector.impl.MilvusVectorService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.collection.FlushParam;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "reachai.mysql.publicationVerification", matches = "true")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MILVUS_HOST", matches = ".+")
class KnowledgeCombinedPublicationIT {
    @Test
    void retrievalOnlySeesCommittedCurrentExecutionAcrossMysqlAndMilvus() throws Exception {
        String collection = "reachai_it_publish_" + UUID.randomUUID().toString().replace("-", "");
        var connection = ConnectParam.newBuilder().withHost(System.getenv("MILVUS_HOST"))
                .withPort(Integer.parseInt(System.getenv("MILVUS_PORT")));
        connection.withAuthorization(System.getenv("MILVUS_USERNAME"), System.getenv("MILVUS_PASSWORD"));
        var client = new MilvusServiceClient(connection.build());
        var vectors = new MilvusVectorService(client);
        try (var source = new KnowledgePublicationMysqlDatabase()) {
            try {
                vectors.ensureCollection(collection, 2);
                var jdbc = new JdbcTemplate(source);
                jdbc.update("INSERT INTO knowledge_base(id,name,code,vector_collection_name,embedding_model_instance_id) VALUES (7,'测试知识库','business-kb',?,'fixture-model')", collection);
                assertEquals("测试知识库", jdbc.queryForObject("SELECT name FROM knowledge_base WHERE id=7", String.class));
                jdbc.update("INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,file_name,file_type,source_object_key,provider_type,status,stage,lease_owner,lease_until,vector_collection_name) VALUES ('job','file',7,'business-kb','a.pdf','pdf','source','DOCLING','INDEXING','INDEXING','stale','2099-01-01 00:00:00',?)", collection);
                var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
                for (var mapper : List.of(KnowledgeBaseRepository.class, FileInfoRepository.class, ChunkRepository.class, DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class)) config.addMapper(mapper);
                var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
                var session = new SqlSessionTemplate(factory.getObject());
                var bases = session.getMapper(KnowledgeBaseRepository.class);
                var files = session.getMapper(FileInfoRepository.class);
                var chunks = session.getMapper(ChunkRepository.class);
                var jobs = session.getMapper(DocumentImportJobRepository.class);
                var transactionManager = new DataSourceTransactionManager(source);
                var executions = KnowledgeIndexTestSupport.executions(session, transactionManager);
                var metadata = new MetadataPersistStep(bases, files, chunks, new com.fasterxml.jackson.databind.ObjectMapper(),
                        new DocumentImportPublicationGuard(jobs, executions, org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeFileDeletionService.class)), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class));
                var current = context(collection, "current", "当前发布正文");
                var stale = context(collection, "stale", "旧执行正文");
                var vectorStep = new VectorStoreStep(vectors, executions);
                jdbc.update("UPDATE knowledge_document_import_job SET vector_collection_name=NULL WHERE job_id='job'");
                assertThrows(com.enterprise.ai.pipeline.PipelineException.class, () -> vectorStep.process(stale));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution", Integer.class));
                assertTrue(stale.getVectorIds().isEmpty());
                jdbc.update("UPDATE knowledge_document_import_job SET vector_collection_name=? WHERE job_id='job'", collection);
                vectorStep.process(stale);
                assertEquals(1, jdbc.queryForObject("SELECT write_acknowledged FROM knowledge_document_index_execution WHERE lease_owner='stale'", Integer.class));
                assertEquals(1, jdbc.update("UPDATE knowledge_document_import_job SET lease_owner='current' WHERE job_id='job' AND lease_owner='stale'"));
                vectorStep.process(current);
                assertNotEquals(current.getVectorIds(), stale.getVectorIds());
                assertEquals(DocumentIndexVectorManifest.forExecution("file", "current", 1).batch(0, 1), current.getVectorIds());
                assertEquals(DocumentIndexVectorManifest.forExecution("file", "stale", 1).batch(0, 1), stale.getVectorIds());
                assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution WHERE state='REGISTERED' AND write_acknowledged=1", Integer.class));
                assertEquals(io.milvus.param.R.Status.Success.getCode(), client.flush(FlushParam.newBuilder().addCollectionName(collection).withSyncFlush(true).build()).getStatus());
                var rawQuery = VectorSearchRequest.builder().collectionName(collection).queryVector(List.of(1.0f, 0.0f)).topK(5).build();
                for (int i=0; i<20 && vectors.search(rawQuery).size()<2; i++) Thread.sleep(250);
                assertEquals(2, vectors.search(rawQuery).size());
                var embedding = mock(com.enterprise.ai.embedding.EmbeddingService.class);
                when(embedding.embed(anyString(), anyString())).thenReturn(List.of(1.0f, 0.0f));
                // Publication verification substitutes authorization; its own real-DB cases cover permission lifecycle.
                var authorization = mock(com.enterprise.ai.retrieval.KnowledgeRetrievalAuthorization.class);
                when(authorization.capture(any())).thenReturn(new com.enterprise.ai.security.FileAccessSnapshot("actor", Map.of(
                        1L, new com.enterprise.ai.security.FileAccessSnapshot.Grant(1L, "a".repeat(32), 1L, "b".repeat(32), 1L, "file", collection))));
                when(authorization.retain(any(), anyList())).thenAnswer(inv -> inv.getArgument(1));
                var core = new KnowledgeRetrievalCore(new DefaultKnowledgeRetrievalEngine(new KnowledgeBaseLookup(bases), files, chunks,
                        mock(KnowledgeHitLogRepository.class), embedding, mock(com.enterprise.ai.client.ModelServiceClient.class), vectors, authorization), authorization);
                var request = KnowledgeRetrievalCoreRequest.builder().knowledgeBaseCodes(List.of("business-kb")).query("正文")
                        .searchMode("vector").topK(5).scoreThreshold(0.1f).rerankEnabled(false).recordHit(false).build();
                assertTrue(core.retrieve(request).getItems().isEmpty());
                var tx = new TransactionTemplate(transactionManager);
                jdbc.update("UPDATE knowledge_document_import_job SET vector_collection_name='different_physical_target' WHERE job_id='job'");
                assertThrows(com.enterprise.ai.pipeline.PipelineException.class, () -> tx.executeWithoutResult(status -> metadata.process(current)));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
                assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution WHERE state='REGISTERED'", Integer.class));
                jdbc.update("UPDATE knowledge_document_import_job SET vector_collection_name=? WHERE job_id='job'", collection);
                assertThrows(com.enterprise.ai.pipeline.PipelineException.class, () -> tx.executeWithoutResult(status -> metadata.process(stale)));
                tx.executeWithoutResult(status -> { metadata.process(current); status.setRollbackOnly(); });
                assertFalse(current.isImportPublished());
                assertEquals("INDEXING", jdbc.queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
                assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution WHERE state='REGISTERED'", Integer.class));
                assertTrue(core.retrieve(request).getItems().isEmpty());
                tx.executeWithoutResult(status -> metadata.process(current));
                assertTrue(current.isImportPublished());
                assertEquals("PUBLISHED", jdbc.queryForObject("SELECT state FROM knowledge_document_index_execution WHERE lease_owner='current'", String.class));
                assertEquals("REGISTERED", jdbc.queryForObject("SELECT state FROM knowledge_document_index_execution WHERE lease_owner='stale'", String.class));
                var result = core.retrieve(request);
                assertEquals(1, result.getItems().size());
                assertEquals("当前发布正文", result.getItems().get(0).getContent());
                assertEquals("business-kb", result.getItems().get(0).getKnowledgeBaseCode());
                assertEquals(collection, jdbc.queryForObject("SELECT collection_name FROM knowledge_chunk", String.class));
                assertEquals(collection, jdbc.queryForObject("SELECT collection_name FROM knowledge_document_index_execution WHERE lease_owner='current'", String.class));
                assertEquals(current.getVectorIds().get(0), result.getItems().get(0).getChunkId());
                assertThrows(com.enterprise.ai.pipeline.PipelineException.class, () -> tx.executeWithoutResult(status -> metadata.process(stale)));
                assertEquals("当前发布正文", core.retrieve(request).getItems().get(0).getContent());
            } finally {
                vectors.dropCollection(collection);
            }
        } finally {
            client.close();
        }
    }

    private static PipelineContext context(String collection, String lease, String text) {
        var context = new PipelineContext(); context.setKnowledgeBaseCode("business-kb"); context.setKnowledgeBaseId(7L); context.setVectorCollectionName(collection); context.setFileId("file");
        context.setFileName("测试文件.pdf"); context.setImportJobId("job"); context.setImportLeaseOwner(lease);
        context.setChunks(List.of(text)); context.setVectors(List.of(List.of(1.0f, 0.0f)));
        return context;
    }
}
