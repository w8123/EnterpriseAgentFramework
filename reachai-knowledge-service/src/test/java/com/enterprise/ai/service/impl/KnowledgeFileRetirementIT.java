package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.KnowledgeImportRequest;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.pipeline.document.job.*;
import com.enterprise.ai.pipeline.step.*;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.security.impl.PermissionServiceImpl;
import com.enterprise.ai.vector.*;
import com.enterprise.ai.vector.impl.MilvusVectorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="reachai.mysql.publicationVerification",matches="true")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="MILVUS_HOST",matches=".+")
class KnowledgeFileRetirementIT {
    @Test
    void deletionRecreationAndFailedCleanupPreserveCurrentDataAndRevokeOldPermissions() throws Exception {
        withStores(f -> {
            f.importFile("file", "原始文件正文"); f.associations();
            String retired=f.jdbc.queryForObject("SELECT vector_id FROM knowledge_chunk",String.class);
            var permissions=new PermissionServiceImpl(f.session.getMapper(UserFilePermissionRepository.class),f.manager);
            assertEquals(List.of("file"),permissions.getAccessibleFileIds("测试用户"));
            f.deletion.deleteFileById("file");
            assertTrue(permissions.getAccessibleFileIds("测试用户").isEmpty());
            assertNull(f.jdbc.queryForObject("SELECT chunk_id FROM knowledge_question",Long.class));
            assertEquals("保留的问题正文",f.jdbc.queryForObject("SELECT question FROM knowledge_question",String.class));
            f.importFile("file", "重新创建后的正文");
            String current=f.jdbc.queryForObject("SELECT vector_id FROM knowledge_chunk",String.class);
            assertNotEquals(retired,current);
            var unavailable=spy(f.remote);
            doThrow(new IllegalStateException("injected remote failure")).when(unavailable).deleteById(f.collection,retired);
            f.reclaimer(unavailable).reclaimPending(); f.assertVectors(Set.of(retired,current));
            f.makeDue(); f.reclaimer(f.remote).reclaimPending(); f.assertVectors(Set.of(current));
            assertTrue(permissions.getAccessibleFileIds("测试用户").isEmpty());
            assertEquals("重新创建后的正文",f.jdbc.queryForObject("SELECT content FROM knowledge_chunk",String.class));
            // Exercise Milvus parsing with an actual historical identifier containing quotes and controls.
            String quoted="历史\\向量\" || id != \"\r\n\t",neighbor="neighbor";
            f.remote.upsert(f.collection,List.of(quoted,neighbor),List.of(List.of(1f,0f),List.of(1f,0f)),List.of("legacy","neighbor"),List.of("旧正文","邻居正文"));
            f.assertVectors(Set.of(current,quoted,neighbor));
            f.remote.deleteById(f.collection,quoted);f.assertVectors(Set.of(current,neighbor));
            System.out.println("MYSQL_MILVUS_FILE_RETIREMENT_VERIFIED mode=recreate_cleanup_permissions_exact_literal");
        });
    }

    @Test
    void replacementRollbackAndCompetingPublicationsKeepOneCommittedFileGeneration() throws Exception {
        withStores(f -> {
            f.importFile("file", "替换前原始正文");f.associations();
            String old=f.jdbc.queryForObject("SELECT vector_id FROM knowledge_chunk",String.class);
            var first=f.replacement("first");var second=f.replacement("second");
            new VectorStoreStep(f.remote,f.store).process(first);new VectorStoreStep(f.remote,f.store).process(second);
            assertThrows(IllegalStateException.class,()->f.tx.executeWithoutResult(status->{f.metadata.process(first);throw new IllegalStateException("rollback after real SQL");}));
            assertFalse(first.isImportPublished());
            assertEquals("替换前原始正文",f.jdbc.queryForObject("SELECT content FROM knowledge_chunk",String.class));
            assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_user_file_permission",Integer.class));
            assertNotNull(f.jdbc.queryForObject("SELECT chunk_id FROM knowledge_question",Long.class));
            f.tx.executeWithoutResult(status->f.metadata.process(first));
            assertTrue(first.isImportPublished());
            assertThrows(PipelineException.class,()->f.tx.executeWithoutResult(status->f.metadata.process(second)));
            assertEquals("COMPLETED",f.jdbc.queryForObject("SELECT status FROM knowledge_document_import_job WHERE job_id='first'",String.class));
            assertEquals("CANCELLED",f.jdbc.queryForObject("SELECT status FROM knowledge_document_import_job WHERE job_id='second'",String.class));
            assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_user_file_permission",Integer.class));
            assertEquals("新解析正文first",f.jdbc.queryForObject("SELECT content FROM knowledge_chunk",String.class));
            f.reclaimer(f.remote).reclaimPending();f.assertVectors(Set.copyOf(first.getVectorIds()));
            assertNotEquals(old,first.getVectorIds().get(0));
            System.out.println("MYSQL_MILVUS_FILE_RETIREMENT_VERIFIED mode=replacement_rollback_competition");
        });
    }

    @Test
    void competingConnectionsCannotReserveTheSameFileAcrossKnowledgeBases() throws Exception {
        try(var source=KnowledgePublicationMysqlDatabase.withFileAssociations(5)) {
            var jdbc=new JdbcTemplate(source);var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
            var started=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
            var pending=new java.util.concurrent.atomic.AtomicReference<Future<?>>();
            String sql="INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,file_name,file_type,source_object_key,provider_type,status,stage) VALUES (?,'same-file',?,'kb','并发任务.txt','txt','fixture/source','JAVA_FAST','QUEUED','QUEUED')";
            try {
                tx.executeWithoutResult(status->{
                    jdbc.update(sql,"first",7);
                    pending.set(executor.submit(()->jdbc.update(connection->{
                        var statement=connection.prepareStatement(sql);statement.setString(1,"second");statement.setLong(2,8);started.countDown();return statement;
                    })));
                    try {assertTrue(started.await(5,TimeUnit.SECONDS));Thread.sleep(300);assertFalse(pending.get().isDone());}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                });
                var failure=assertThrows(ExecutionException.class,()->pending.get().get(10,TimeUnit.SECONDS));
                assertInstanceOf(org.springframework.dao.DuplicateKeyException.class,failure.getCause());
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_import_job",Integer.class));
                jdbc.update("UPDATE knowledge_document_import_job SET status='CANCELLED' WHERE job_id='first'");
                jdbc.update(sql,"second",8);
                assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_import_job",Integer.class));
                assertEquals("并发任务.txt",jdbc.queryForObject("SELECT file_name FROM knowledge_document_import_job WHERE job_id='second'",String.class));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_import_job WHERE active_file_id='same-file'",Integer.class));
                System.out.println("MYSQL_FILE_RESERVATION_CONCURRENCY_VERIFIED");
            } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
        }
    }

    private interface StoreTest {void run(Fixture fixture)throws Exception;}
    private static void withStores(StoreTest test)throws Exception {
        String collection="reachai_it_file_"+UUID.randomUUID().toString().replace("-","");
        var connection=ConnectParam.newBuilder().withHost(System.getenv("MILVUS_HOST")).withPort(Integer.parseInt(System.getenv("MILVUS_PORT")))
                .withAuthorization(System.getenv("MILVUS_USERNAME"),System.getenv("MILVUS_PASSWORD"));
        var client=new MilvusServiceClient(connection.build());var remote=new MilvusVectorService(client);
        try(var source=KnowledgePublicationMysqlDatabase.withFileAssociations(5)) {
            try {
                remote.ensureCollection(collection,2);System.out.println("FILE_RETIREMENT_COLLECTION_CREATED "+collection);
                test.run(new Fixture(source,collection,client,remote));
            } finally {
                remote.dropCollection(collection);
                var exists=client.hasCollection(HasCollectionParam.newBuilder().withCollectionName(collection).build());
                assertEquals(io.milvus.param.R.Status.Success.getCode(),exists.getStatus());assertEquals(false,exists.getData());
                System.out.println("FILE_RETIREMENT_COLLECTION_REMOVED "+collection);
            }
        } finally {client.close();}
    }

    private static class Fixture {
        final JdbcTemplate jdbc;final SqlSessionTemplate session;final DataSourceTransactionManager manager;final TransactionTemplate tx;
        final String collection;final MilvusServiceClient client;final MilvusVectorService remote;
        final DocumentIndexExecutionStore store;final KnowledgeFileDeletionService deletion;final MetadataPersistStep metadata;final KnowledgeIndexWriteService writer;
        Fixture(KnowledgePublicationMysqlDatabase source,String collection,MilvusServiceClient client,MilvusVectorService remote)throws Exception {
            this.collection=collection;this.client=client;this.remote=remote;jdbc=new JdbcTemplate(source);session=KnowledgeWriteLifecycleIT.session(source);
            session.getConfiguration().addMapper(UserFilePermissionRepository.class);session.getConfiguration().addMapper(KnowledgeQuestionRepository.class);
            manager=new DataSourceTransactionManager(source);tx=new TransactionTemplate(manager);store=KnowledgeIndexTestSupport.executions(session,manager);
            deletion=new KnowledgeFileDeletionService(session.getMapper(KnowledgeBaseRepository.class),session.getMapper(FileInfoRepository.class),session.getMapper(ChunkRepository.class),
                    session.getMapper(DocumentImportJobRepository.class),session.getMapper(DocumentIndexExecutionRepository.class),store,mock(DocumentArtifactStore.class),
                    session.getMapper(UserFilePermissionRepository.class), new com.enterprise.ai.service.impl.KnowledgeQuestionService(session.getMapper(KnowledgeBaseRepository.class), session.getMapper(ChunkRepository.class), session.getMapper(KnowledgeQuestionRepository.class), manager),manager, org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class));
            metadata=new MetadataPersistStep(session.getMapper(KnowledgeBaseRepository.class),session.getMapper(FileInfoRepository.class),session.getMapper(ChunkRepository.class),
                    new ObjectMapper(),new DocumentImportPublicationGuard(session.getMapper(DocumentImportJobRepository.class),store,deletion), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class));
            var embedding=mock(EmbeddingService.class);when(embedding.embedBatch(anyString(),anyList())).thenReturn(List.of(List.of(1f,0f)));
            writer=new KnowledgeIndexWriteService(new KnowledgeBaseLookup(session.getMapper(KnowledgeBaseRepository.class)),session.getMapper(FileInfoRepository.class),session.getMapper(ChunkRepository.class),
                    embedding,new VectorStoreStep(remote,store),metadata,store,manager);
            jdbc.update("INSERT INTO knowledge_base(id,name,code,vector_collection_name,dimension,embedding_model_instance_id) VALUES (7,'真实文件退场测试','kb',?,2,'fixture-model')",collection);
            assertEquals("真实文件退场测试",jdbc.queryForObject("SELECT name FROM knowledge_base",String.class));
        }
        void importFile(String file,String content) {var request=new KnowledgeImportRequest();request.setKnowledgeBaseCode("kb");request.setFileId(file);request.setFileName("文件退场测试.txt");request.setChunks(List.of(content));writer.importChunks(request);}
        void associations() {
            jdbc.update("INSERT INTO knowledge_user_file_permission(user_id,file_id) VALUES ('测试用户','file')");
            jdbc.update("INSERT INTO knowledge_question(knowledge_base_id,chunk_id,question) SELECT 7,id,'保留的问题正文' FROM knowledge_chunk WHERE file_id='file'");
        }
        PipelineContext replacement(String job) {
            String lease=UUID.randomUUID().toString(),file="new-"+job;Long original=jdbc.queryForObject("SELECT id FROM knowledge_file_info WHERE file_id='file'",Long.class);
            String generation=jdbc.queryForObject("SELECT record_generation FROM knowledge_file_info WHERE file_id='file'",String.class);
            jdbc.update("INSERT INTO knowledge_document_import_job(job_id,file_id,replace_file_id,replace_file_row_id,replace_file_generation,knowledge_base_id,knowledge_base_code,vector_collection_name,file_name,file_type,source_object_key,provider_type,status,stage,lease_owner,lease_until) VALUES (?,?,'file',?,?,7,'kb',?,'新解析.txt','txt','fixture/source','JAVA_FAST','INDEXING','INDEXING',?,TIMESTAMPADD(SECOND,1200,CURRENT_TIMESTAMP))",job,file,original,generation,collection,lease);
            var c=new PipelineContext();c.setKnowledgeBaseId(7L);c.setKnowledgeBaseCode("kb");c.setVectorCollectionName(collection);c.setKnowledgeBaseDimension(2);
            c.setFileId(file);c.setFileName("新解析.txt");c.setChunks(List.of("新解析正文"+job));c.setVectors(List.of(List.of(1f,0f)));c.setImportJobId(job);c.setImportLeaseOwner(lease);return c;
        }
        DocumentIndexReclaimer reclaimer(VectorService vectors) {return new DocumentIndexReclaimer(session.getMapper(DocumentIndexExecutionRepository.class),KnowledgeIndexTestSupport.executions(session,manager),vectors,new DocumentImportJobProperties());}
        void makeDue(){jdbc.update("UPDATE knowledge_document_index_execution SET next_cleanup_at='2000-01-01 00:00:00' WHERE state='RECLAIMING'");}
        void assertVectors(Set<String> expected)throws Exception {
            assertEquals(io.milvus.param.R.Status.Success.getCode(),client.flush(FlushParam.newBuilder().addCollectionName(collection).withSyncFlush(true).build()).getStatus());
            var query=VectorSearchRequest.builder().collectionName(collection).queryVector(List.of(1f,0f)).topK(10).build();Set<String> actual=Set.of();
            for(int i=0;i<20;i++){actual=new HashSet<>(remote.search(query).stream().map(VectorSearchResult::getId).toList());if(actual.equals(expected))break;Thread.sleep(250);}
            assertEquals(expected,actual);
        }
    }
}
