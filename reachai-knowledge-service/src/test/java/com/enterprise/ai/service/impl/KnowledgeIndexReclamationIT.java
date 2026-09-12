package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.document.job.*;
import com.enterprise.ai.pipeline.step.VectorStoreStep;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.vector.*;
import com.enterprise.ai.vector.impl.MilvusVectorService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.collection.FlushParam;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real stores with controlled write/delete interleaving; does not simulate process termination. */
@EnabledIfSystemProperty(named = "reachai.mysql.publicationVerification", matches = "true")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MILVUS_HOST", matches = ".+")
class KnowledgeIndexReclamationIT {
    @ParameterizedTest
    @ValueSource(strings = {"unknown", "lateAck", "partial"})
    void recoversPersistedIdentityAndProgressAcrossMysqlAndMilvus(String mode) throws Exception {
        String collection = "reachai_it_reclaim_" + UUID.randomUUID().toString().replace("-", "");
        var connection = ConnectParam.newBuilder().withHost(System.getenv("MILVUS_HOST"))
                .withPort(Integer.parseInt(System.getenv("MILVUS_PORT")))
                .withAuthorization(System.getenv("MILVUS_USERNAME"), System.getenv("MILVUS_PASSWORD"));
        var client = new MilvusServiceClient(connection.build());
        var vectors = new MilvusVectorService(client);
        try (var source = new KnowledgePublicationMysqlDatabase()) {
            try {
                vectors.ensureCollection(collection, 2);
                var jdbc = new JdbcTemplate(source);
                jdbc.update("INSERT INTO knowledge_base(id,name,code,vector_collection_name) VALUES (7,'reclamation target',?,?)", collection, collection);
                jdbc.update("INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,file_name,file_type,source_object_key,provider_type,status,stage,lease_owner,lease_until,vector_collection_name) VALUES ('job','file',7,?,'a.pdf','pdf','source','DOCLING','INDEXING','INDEXING','lease','2099-01-01 00:00:00',?)", collection, collection);
                var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
                config.addMapper(DocumentImportJobRepository.class); config.addMapper(DocumentIndexExecutionRepository.class);
                var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
                var session = new SqlSessionTemplate(factory.getObject());
                var jobs = session.getMapper(DocumentImportJobRepository.class);
                var executions = session.getMapper(DocumentIndexExecutionRepository.class);
                var manager = new DataSourceTransactionManager(source);
                var store = KnowledgeIndexTestSupport.executions(session, manager);
                var context = new PipelineContext(); context.setFileId("file"); context.setImportJobId("job");
                context.setImportLeaseOwner("lease"); context.setKnowledgeBaseCode(collection); context.setKnowledgeBaseId(7L); context.setVectorCollectionName(collection);
                context.setChunks(Collections.nCopies(3, "回收测试正文")); context.setVectors(Collections.nCopies(3, List.of(1.0f, 0.0f)));
                var manifest = DocumentIndexVectorManifest.forExecution("file", "lease", 3);
                if (mode.equals("partial")) {
                    var step = new VectorStoreStep(vectors, store);
                    step.process(context);
                    assertCount(client, vectors, collection, 3);
                } else assertTrue(store.register(context, manifest));
                jdbc.update("UPDATE knowledge_document_import_job SET lease_until='2000-01-01 00:00:00'");
                assertEquals(1, jobs.failExpiredIndexingLeases()); // Lease is gone, journal must preserve all identities.
                var failing = spy(vectors);
                if (!mode.equals("unknown")) doThrow(new IllegalStateException("injected delete failure"))
                        .when(failing).deleteById(collection, manifest.vectorId(1));
                new DocumentIndexReclaimer(executions, store, failing, new DocumentImportJobProperties()).reclaimPending();
                assertEquals("RECLAIMING", executions.selectById("lease").getState());
                assertEquals(mode.equals("unknown") ? 0 : 1, executions.selectById("lease").getCleanupCursor());
                if (!mode.equals("partial")) {
                    // Complete the original remote write only after the first sweep (or partial sweep).
                    vectors.upsert(collection, manifest.batch(0, 10), context.getVectors(), Collections.nCopies(3, "file"), context.getChunks());
                    assertCount(client, vectors, collection, 3);
                    if (mode.equals("lateAck")) {
                        store.acknowledge("lease");
                        assertEquals(0, executions.selectById("lease").getCleanupCursor());
                    }
                }
                jdbc.update("UPDATE knowledge_document_index_execution SET next_cleanup_at='2000-01-01 00:00:00'");
                // Rebuild the store and scheduler; no old PipelineContext or in-memory ID list is used by recovery.
                var restarted = KnowledgeIndexTestSupport.executions(session, manager);
                new DocumentIndexReclaimer(executions, restarted, vectors, new DocumentImportJobProperties()).reclaimPending();
                assertCount(client, vectors, collection, 0);
                assertEquals(mode.equals("unknown") ? "RECLAIMING" : "RECLAIMED", executions.selectById("lease").getState());
                assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
                System.out.println("MYSQL_MILVUS_RECLAMATION_VERIFIED mode=" + mode);
            } finally { vectors.dropCollection(collection); }
        } finally { client.close(); }
    }

    private static void assertCount(MilvusServiceClient client, MilvusVectorService vectors, String collection, int count) throws InterruptedException {
        assertEquals(io.milvus.param.R.Status.Success.getCode(), client.flush(FlushParam.newBuilder().addCollectionName(collection).withSyncFlush(true).build()).getStatus());
        var query = VectorSearchRequest.builder().collectionName(collection).queryVector(List.of(1.0f, 0.0f)).topK(5).build();
        for (int i=0; i<20 && vectors.search(query).size()!=count; i++) Thread.sleep(250);
        assertEquals(count, vectors.search(query).size());
    }
}
