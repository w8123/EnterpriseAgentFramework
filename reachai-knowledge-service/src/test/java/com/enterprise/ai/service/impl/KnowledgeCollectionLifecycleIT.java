package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.KnowledgeBaseRequest;
import com.enterprise.ai.domain.dto.KnowledgeImportRequest;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.pipeline.document.job.*;
import com.enterprise.ai.pipeline.step.*;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.vector.VectorService;
import com.enterprise.ai.vector.impl.MilvusVectorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.collection.HasCollectionParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="reachai.mysql.publicationVerification",matches="true")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="MILVUS_HOST",matches=".+")
class KnowledgeCollectionLifecycleIT {
    @Test
    void deletingAndRecreatingARealKnowledgeBaseRetiresOnlyTheOldPhysicalCollection()throws Exception {
        withStores(f->{
            var old=f.create("kb");f.importFile();
            f.jdbc.update("INSERT INTO knowledge_user_file_permission(user_id,file_id) VALUES ('测试用户','file')");
            f.jdbc.update("INSERT INTO knowledge_question(knowledge_base_id,question) VALUES (?,'原问题正文')",old.getId());
            f.jdbc.update("INSERT INTO knowledge_tag(knowledge_base_id,target_type,target_id,tag_key,tag_value) VALUES (?,'FILE','file','分类','原标签')",old.getId());
            f.lifecycle.deleteByCode("kb");assertEquals(0,f.count("knowledge_base"));
            assertEquals("RECLAIMING",f.state(old.getVectorCollectionName()));assertTrue(f.exists(old.getVectorCollectionName()));
            for(String table:List.of("knowledge_file_info","knowledge_chunk","knowledge_question","knowledge_tag","knowledge_user_file_permission"))assertEquals(0,f.count(table),table);
            var current=f.create("kb");assertNotEquals(old.getId(),current.getId());assertNotEquals(old.getVectorCollectionName(),current.getVectorCollectionName());
            f.reclaimer().reclaimPending();assertFalse(f.exists(old.getVectorCollectionName()));assertTrue(f.exists(current.getVectorCollectionName()));
            assertEquals("RECLAIMED",f.state(old.getVectorCollectionName()));assertEquals("READY",f.state(current.getVectorCollectionName()));
            // The vector reclaimer must also handle an already-dropped collection through a verified absence read.
            new DocumentIndexReclaimer(f.session.getMapper(DocumentIndexExecutionRepository.class),f.executions,f.remote,new DocumentImportJobProperties()).reclaimPending();
            assertEquals("RECLAIMED",f.jdbc.queryForObject("SELECT state FROM knowledge_document_index_execution",String.class));
            assertEquals("实库生命周期知识库",f.jdbc.queryForObject("SELECT name FROM knowledge_base",String.class));
            System.out.println("MYSQL_MILVUS_COLLECTION_LIFECYCLE_VERIFIED mode=delete_recreate_associations");
        });
    }

    @Test
    void anUnknownCreationOutcomeRemainsReclaimableAfterARealLateRecreation()throws Exception {
        withStores(f->{
            f.failAfterCreate=true;
            assertThrows(IllegalStateException.class,()->f.create("kb"));
            String collection=f.owned.iterator().next();assertTrue(f.exists(collection));assertEquals(0,f.count("knowledge_base"));
            f.reclaimer().reclaimPending();assertFalse(f.exists(collection));assertEquals("RECLAIMING",f.state(collection));
            // Controlled late remote completion: the first success was hidden from the application above.
            f.remote.ensureCollection(collection,2);assertTrue(f.exists(collection));
            f.jdbc.update("UPDATE knowledge_collection_lifecycle SET next_cleanup_at='2000-01-01 00:00:00'");
            f.reclaimer().reclaimPending();assertFalse(f.exists(collection));assertEquals("RECLAIMING",f.state(collection));
            assertEquals(0,f.jdbc.queryForObject("SELECT create_acknowledged FROM knowledge_collection_lifecycle",Integer.class));
            System.out.println("MYSQL_MILVUS_COLLECTION_LIFECYCLE_VERIFIED mode=unknown_late_creation");
        });
    }

    @Test
    void publishingWhileCleanupWaitsOnTheJournalDoesNotAcquireAnInvertedBaseGapLock()throws Exception {
        withStores(f->{
            var base=f.provisioned();var candidate=f.session.getMapper(KnowledgeCollectionLifecycleRepository.class).selectById(base.getVectorCollectionName());
            var gate=new ConcurrentGate();f.session.getConfiguration().addInterceptor(gate);
            var pool=Executors.newSingleThreadExecutor();var pending=new AtomicReference<Future<?>>();
            try {
                gate.beforeBaseInsert=()->{
                    pending.set(pool.submit(()->{gate.observedThread.set(Thread.currentThread());return f.store.claim(candidate);}));
                    awaitBlocked(gate.collectionLockStarted,pending.get());
                };
                f.store.publish(base);
                assertNull(pending.get().get(10,TimeUnit.SECONDS));
                assertEquals(1,f.count("knowledge_base"));assertEquals("READY",f.state(base.getVectorCollectionName()));
                assertTrue(f.exists(base.getVectorCollectionName()));
                System.out.println("MYSQL_COLLECTION_PUBLICATION_LOCK_ORDER_VERIFIED");
            }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        });
    }

    @Test
    void publicationRechecksItsDatabaseDeadlineAfterTheJournalRowLockWait()throws Exception {
        withStores(f->{
            var base=f.provisioned();var gate=new ConcurrentGate();f.session.getConfiguration().addInterceptor(gate);
            var pool=Executors.newSingleThreadExecutor();var pending=new AtomicReference<Future<?>>();
            try {
                new TransactionTemplate(f.manager).executeWithoutResult(status->{
                    f.jdbc.queryForList("SELECT collection_name FROM knowledge_collection_lifecycle WHERE collection_name=? FOR UPDATE",base.getVectorCollectionName());
                    pending.set(pool.submit(()->{gate.observedThread.set(Thread.currentThread());f.store.publish(base);}));
                    awaitBlocked(gate.collectionLockStarted,pending.get());
                    f.jdbc.update("UPDATE knowledge_collection_lifecycle SET create_deadline=CURRENT_TIMESTAMP WHERE collection_name=?",base.getVectorCollectionName());
                    try{Thread.sleep(1100);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                });
                var failure=assertThrows(ExecutionException.class,()->pending.get().get(10,TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class,failure.getCause());assertEquals(0,f.count("knowledge_base"));
                f.reclaimer().reclaimPending();assertEquals("RECLAIMED",f.state(base.getVectorCollectionName()));assertFalse(f.exists(base.getVectorCollectionName()));
                System.out.println("MYSQL_COLLECTION_DEADLINE_AFTER_LOCK_VERIFIED");
            }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        });
    }

    private static void awaitBlocked(CountDownLatch started,Future<?> pending) {
        try{assertTrue(started.await(5,TimeUnit.SECONDS));Thread.sleep(300);assertFalse(pending.isDone());}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    private interface StoreTest {void run(Fixture f)throws Exception;}
    private static void withStores(StoreTest test)throws Exception {
        try(var source=KnowledgePublicationMysqlDatabase.withCollectionLifecycle(5)){
            try(var f=new Fixture(source)){test.run(f);}
        }
    }

    private static class Fixture implements AutoCloseable {
        final JdbcTemplate jdbc;final SqlSessionTemplate session;final DataSourceTransactionManager manager;
        final MilvusServiceClient client;final MilvusVectorService remote;final VectorService guarded;
        final KnowledgeCollectionLifecycleStore store;final KnowledgeBaseLifecycleService lifecycle;
        final DocumentIndexExecutionStore executions;final KnowledgeIndexWriteService writer;
        final Set<String> owned=new LinkedHashSet<>();boolean failAfterCreate;
        Fixture(KnowledgePublicationMysqlDatabase source)throws Exception {
            jdbc=new JdbcTemplate(source);session=KnowledgeWriteLifecycleIT.session(source);manager=new DataSourceTransactionManager(source);
            for(var type:List.of(KnowledgeCollectionLifecycleRepository.class,KnowledgeQuestionRepository.class,KnowledgeTagRepository.class,UserFilePermissionRepository.class))session.getConfiguration().addMapper(type);
            var connect=ConnectParam.newBuilder().withHost(System.getenv("MILVUS_HOST")).withPort(Integer.parseInt(System.getenv("MILVUS_PORT")))
                    .withAuthorization(System.getenv("MILVUS_USERNAME"),System.getenv("MILVUS_PASSWORD"));
            client=new MilvusServiceClient(connect.build());remote=new MilvusVectorService(client);guarded=spy(remote);
            doAnswer(call->{
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());String collection=call.getArgument(0);
                assertTrue(collection.matches("reachai_kb_[a-f0-9]{32}"));assertFalse(exists(collection));owned.add(collection);
                System.out.println("COLLECTION_LIFECYCLE_REMOTE_ALLOCATED "+collection);
                Object result=call.callRealMethod();if(failAfterCreate)throw new IllegalStateException("injected lost create result");return result;
            }).when(guarded).ensureCollection(anyString(),anyInt());
            var bases=session.getMapper(KnowledgeBaseRepository.class);var chunks=session.getMapper(ChunkRepository.class);
            executions=KnowledgeIndexTestSupport.executions(session,manager);
            var files=new KnowledgeFileDeletionService(bases,session.getMapper(FileInfoRepository.class),chunks,session.getMapper(DocumentImportJobRepository.class),
                    session.getMapper(DocumentIndexExecutionRepository.class),executions,mock(DocumentArtifactStore.class),session.getMapper(UserFilePermissionRepository.class), new com.enterprise.ai.service.impl.KnowledgeQuestionService(bases, chunks, session.getMapper(KnowledgeQuestionRepository.class), manager),manager, org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class));
            store=new KnowledgeCollectionLifecycleStore(session.getMapper(KnowledgeCollectionLifecycleRepository.class),bases,chunks,manager);
            lifecycle=new KnowledgeBaseLifecycleService(bases,session.getMapper(KnowledgeTagRepository.class), new com.enterprise.ai.service.impl.KnowledgeQuestionService(bases, org.mockito.Mockito.mock(com.enterprise.ai.repository.ChunkRepository.class), session.getMapper(KnowledgeQuestionRepository.class), manager),files,store,guarded,manager);
            var metadata=new MetadataPersistStep(bases,session.getMapper(FileInfoRepository.class),chunks,new ObjectMapper(),new DocumentImportPublicationGuard(session.getMapper(DocumentImportJobRepository.class),executions,files), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class));
            var embedding=mock(EmbeddingService.class);when(embedding.embedBatch(anyString(),anyList())).thenReturn(List.of(List.of(1f,0f)));
            writer=new KnowledgeIndexWriteService(new KnowledgeBaseLookup(bases),session.getMapper(FileInfoRepository.class),chunks,embedding,new VectorStoreStep(remote,executions),metadata,executions,manager);
        }
        KnowledgeBase create(String code){
            var request=new KnowledgeBaseRequest();request.setName("实库生命周期知识库");request.setCode(code);request.setDimension(2);request.setEmbeddingModelInstanceId("fixture-model");request.setLlmModelInstanceId("fixture-chat");
            lifecycle.create(request);return session.getMapper(KnowledgeBaseRepository.class).selectById(jdbc.queryForObject("SELECT id FROM knowledge_base WHERE code=?",Long.class,code));
        }
        KnowledgeBase provisioned(){
            var base=new KnowledgeBase();base.setCode("kb");base.setName("等待发布的中文知识库");base.setDimension(2);base.setVectorCollectionName("reachai_kb_"+UUID.randomUUID().toString().replace("-",""));
            store.register(base);guarded.ensureCollection(base.getVectorCollectionName(),2);store.acknowledge(base.getVectorCollectionName());return base;
        }
        void importFile(){var request=new KnowledgeImportRequest();request.setKnowledgeBaseCode("kb");request.setFileId("file");request.setFileName("原文件.txt");request.setChunks(List.of("真实整库退场正文"));writer.importChunks(request);assertEquals("真实整库退场正文",jdbc.queryForObject("SELECT content FROM knowledge_chunk",String.class));}
        KnowledgeCollectionReclaimer reclaimer(){return new KnowledgeCollectionReclaimer(session.getMapper(KnowledgeCollectionLifecycleRepository.class),new KnowledgeCollectionLifecycleStore(session.getMapper(KnowledgeCollectionLifecycleRepository.class),session.getMapper(KnowledgeBaseRepository.class),session.getMapper(ChunkRepository.class),manager),remote);}
        String state(String collection){return jdbc.queryForObject("SELECT state FROM knowledge_collection_lifecycle WHERE collection_name=?",String.class,collection);}
        int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
        boolean exists(String collection){var response=client.hasCollection(HasCollectionParam.newBuilder().withCollectionName(collection).build());assertEquals(io.milvus.param.R.Status.Success.getCode(),response.getStatus());assertNotNull(response.getData());return response.getData();}
        @Override public void close()throws Exception {
            Exception failure=null;
            try {
                for(String collection:owned)try{remote.dropCollection(collection);assertFalse(exists(collection));System.out.println("COLLECTION_LIFECYCLE_REMOTE_REMOVED "+collection);}
                catch(Exception|AssertionError error){if(failure==null)failure=new IllegalStateException("Owned test collection cleanup failed",error);else failure.addSuppressed(error);}
                if(failure!=null)throw failure;
            }finally{client.close();}
        }
    }

    @org.apache.ibatis.plugin.Intercepts({
        @org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="update",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class}),
        @org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.statement.StatementHandler.class,method="query",args={java.sql.Statement.class,org.apache.ibatis.session.ResultHandler.class})
    })
    static class ConcurrentGate implements org.apache.ibatis.plugin.Interceptor {
        final AtomicReference<Thread> observedThread=new AtomicReference<>();final CountDownLatch collectionLockStarted=new CountDownLatch(1);Runnable beforeBaseInsert=()->{};
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation)throws Throwable {
            if(invocation.getTarget() instanceof org.apache.ibatis.executor.Executor){
                var statement=(org.apache.ibatis.mapping.MappedStatement)invocation.getArgs()[0];
                if(statement.getId().contains("KnowledgeBaseRepository.")&&statement.getSqlCommandType()==org.apache.ibatis.mapping.SqlCommandType.INSERT)beforeBaseInsert.run();
            }else if(Thread.currentThread()==observedThread.get()){
                var statement=(org.apache.ibatis.executor.statement.StatementHandler)invocation.getTarget();String sql=statement.getBoundSql().getSql();
                if(sql.contains("knowledge_collection_lifecycle")&&sql.contains("FOR UPDATE"))collectionLockStarted.countDown();
            }
            return invocation.proceed();
        }
    }
}
