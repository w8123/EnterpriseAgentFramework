package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.config.MyBatisPlusConfig;
import com.enterprise.ai.domain.dto.KnowledgeImportRequest;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.document.job.*;
import com.enterprise.ai.pipeline.step.*;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.vector.*;
import com.enterprise.ai.vector.impl.MilvusVectorService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="reachai.mysql.publicationVerification",matches="true")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="MILVUS_HOST",matches=".+")
class KnowledgeWriteLifecycleIT {
    @Test
    void directImportReplacementAndRejectedLatePublicationKeepTheCurrentVectorAcrossRealStores() throws Exception {
        String collection="reachai_it_write_"+UUID.randomUUID().toString().replace("-","");
        var connection=ConnectParam.newBuilder().withHost(System.getenv("MILVUS_HOST"))
                .withPort(Integer.parseInt(System.getenv("MILVUS_PORT")))
                .withAuthorization(System.getenv("MILVUS_USERNAME"),System.getenv("MILVUS_PASSWORD"));
        var client=new MilvusServiceClient(connection.build());
        var remote=new MilvusVectorService(client);
        try(var source=new KnowledgePublicationMysqlDatabase()) {
            try {
                remote.ensureCollection(collection,2);
                var jdbc=new JdbcTemplate(source); seed(jdbc,collection);
                var session=session(source);var manager=new DataSourceTransactionManager(source);
                var store=KnowledgeIndexTestSupport.executions(session,manager);
                var calls=new CopyOnWriteArrayList<List<String>>();
                var conflict=new AtomicBoolean(false);
                var vectors=spy(remote);
                doAnswer(call-> {
                    assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                    calls.add(List.copyOf(call.<List<String>>getArgument(1)));
                    Object result=call.callRealMethod();
                    if(calls.size()==2)assertEquals(calls.get(0).get(0),jdbc.queryForObject("SELECT vector_id FROM knowledge_chunk",String.class));
                    if(conflict.get())jdbc.update("UPDATE knowledge_chunk SET content='并发修改正文' WHERE file_id='file'");
                    return result;
                }).when(vectors).upsert(anyString(),anyList(),anyList(),anyList(),anyList());
                var embedding=mock(EmbeddingService.class);
                when(embedding.embedBatch(anyString(),anyList())).thenReturn(List.of(List.of(1f,0f)));
                when(embedding.embed(anyString(),anyString())).thenReturn(List.of(1f,0f));
                var metadata=new MetadataPersistStep(session.getMapper(KnowledgeBaseRepository.class),session.getMapper(FileInfoRepository.class),
                        session.getMapper(ChunkRepository.class),new ObjectMapper(),new DocumentImportPublicationGuard(session.getMapper(DocumentImportJobRepository.class),store, org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeFileDeletionService.class)), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class));
                var writer=new KnowledgeIndexWriteService(new KnowledgeBaseLookup(session.getMapper(KnowledgeBaseRepository.class)),
                        session.getMapper(FileInfoRepository.class),session.getMapper(ChunkRepository.class),embedding,new VectorStoreStep(vectors,store),metadata,store,manager);
                var request=new KnowledgeImportRequest();request.setKnowledgeBaseCode("write-business-kb");request.setFileId("file");
                request.setFileName("真实发布文件.txt");request.setChunks(List.of("真实发布正文"));
                writer.importChunks(request);
                assertEquals("真实发布文件.txt",jdbc.queryForObject("SELECT file_name FROM knowledge_file_info",String.class));
                assertEquals("真实发布正文",jdbc.queryForObject("SELECT content FROM knowledge_chunk",String.class));
                Long chunk=jdbc.queryForObject("SELECT id FROM knowledge_chunk",Long.class);
                writer.reembedChunk(chunk);
                String current=jdbc.queryForObject("SELECT vector_id FROM knowledge_chunk",String.class);
                assertNotEquals(calls.get(0).get(0),current);assertEquals(calls.get(1).get(0),current);
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution WHERE operation_type='RETIRED_VECTOR' AND write_acknowledged=1",Integer.class));
                var reclaimer=new DocumentIndexReclaimer(session.getMapper(DocumentIndexExecutionRepository.class),store,vectors,new DocumentImportJobProperties());
                reclaimer.reclaimPending(); assertVectors(client,remote,collection,Set.of(current));
                assertEquals("RECLAIMED",jdbc.queryForObject("SELECT state FROM knowledge_document_index_execution WHERE operation_type='RETIRED_VECTOR'",String.class));
                conflict.set(true);
                assertThrows(PipelineException.class,()->writer.reembedChunk(chunk));
                assertEquals(current,jdbc.queryForObject("SELECT vector_id FROM knowledge_chunk",String.class));
                assertEquals("并发修改正文",jdbc.queryForObject("SELECT content FROM knowledge_chunk",String.class));
                reclaimer.reclaimPending(); assertVectors(client,remote,collection,Set.of(current));
                assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution WHERE state='PUBLISHED'",Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution WHERE operation_type='REEMBED' AND state='RECLAIMED'",Integer.class));
                System.out.println("MYSQL_MILVUS_WRITE_LIFECYCLE_VERIFIED collection="+collection);
            } finally {remote.dropCollection(collection);}
        } finally {client.close();}
    }

    @Test
    void standalonePublicationRechecksDatabaseDeadlineAfterWaitingForTheExecutionRow() throws Exception {
        try(var source=new KnowledgePublicationMysqlDatabase(5)) {
            var jdbc=new JdbcTemplate(source);seed(jdbc,"deadline_target");
            var session=session(source);var manager=new DataSourceTransactionManager(source);
            var observer=new WaitingExecutionRead();session.getConfiguration().addInterceptor(observer);
            var store=KnowledgeIndexTestSupport.executions(session,manager);
            var context=new PipelineContext();context.setKnowledgeBaseId(7L);context.setKnowledgeBaseCode("write-business-kb");
            context.setVectorCollectionName("deadline_target");context.setFileId("file");context.setChunks(List.of("text"));
            context.setIndexExecutionId(UUID.randomUUID().toString());context.setIndexOperation("DIRECT");
            var manifest=DocumentIndexVectorManifest.forExecution("file",context.getIndexExecutionId(),1);context.setVectorIds(manifest.batch(0,1));
            assertTrue(store.register(context,manifest));store.acknowledge(context.getIndexExecutionId());
            var executor=Executors.newSingleThreadExecutor();var pending=new AtomicReference<Future<?>>();
            try {
                new TransactionTemplate(manager).executeWithoutResult(status-> {
                    jdbc.queryForList("SELECT lease_owner FROM knowledge_document_index_execution WHERE lease_owner=? FOR UPDATE",context.getIndexExecutionId());
                    observer.enabled=true;
                    pending.set(executor.submit(()->new TransactionTemplate(manager).executeWithoutResult(s->store.lockForPublication(context,7L))));
                    try {
                        assertTrue(observer.started.await(5,TimeUnit.SECONDS));Thread.sleep(1100);
                        assertFalse(pending.get().isDone());
                        jdbc.update("UPDATE knowledge_document_index_execution SET publication_deadline=CURRENT_TIMESTAMP WHERE lease_owner=?",context.getIndexExecutionId());
                        Thread.sleep(200);
                    } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                });
                var failure=assertThrows(ExecutionException.class,()->pending.get().get(10,TimeUnit.SECONDS));
                assertInstanceOf(PipelineException.class,failure.getCause());
                assertEquals("REGISTERED",jdbc.queryForObject("SELECT state FROM knowledge_document_index_execution",String.class));
                assertEquals(1,session.getMapper(DocumentIndexExecutionRepository.class).findReclaimable(8).size());
                System.out.println("MYSQL_STANDALONE_DEADLINE_VERIFIED");
            } finally {executor.shutdownNow();}
        }
    }

    static SqlSessionTemplate session(javax.sql.DataSource source) throws Exception {
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        var global=GlobalConfigUtils.defaults().setMetaObjectHandler(new MyBatisPlusConfig().metaObjectHandler());GlobalConfigUtils.setGlobalConfig(config,global);
        for(var mapper:List.of(KnowledgeBaseRepository.class,FileInfoRepository.class,ChunkRepository.class,DocumentImportJobRepository.class,DocumentIndexExecutionRepository.class))config.addMapper(mapper);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);factory.setGlobalConfig(global);
        return new SqlSessionTemplate(factory.getObject());
    }

    private static void seed(JdbcTemplate jdbc,String collection) {
        jdbc.update("INSERT INTO knowledge_base(id,name,code,vector_collection_name,dimension,embedding_model_instance_id) VALUES (7,'写入生命周期测试','write-business-kb',?,2,'fixture-model')",collection);
        assertEquals("写入生命周期测试",jdbc.queryForObject("SELECT name FROM knowledge_base",String.class));
    }

    private static void assertVectors(MilvusServiceClient client,MilvusVectorService vectors,String collection,Set<String> expected) throws Exception {
        assertEquals(io.milvus.param.R.Status.Success.getCode(),client.flush(FlushParam.newBuilder().addCollectionName(collection).withSyncFlush(true).build()).getStatus());
        var request=VectorSearchRequest.builder().collectionName(collection).queryVector(List.of(1f,0f)).topK(10).build();
        Set<String> actual=Set.of();
        for(int i=0;i<20;i++){
            actual=new HashSet<>(vectors.search(request).stream().map(VectorSearchResult::getId).toList());
            if(actual.equals(expected))break;Thread.sleep(250);
        }
        assertEquals(expected,actual);
    }

    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.statement.StatementHandler.class,method="query",args={java.sql.Statement.class,org.apache.ibatis.session.ResultHandler.class}))
    static class WaitingExecutionRead implements org.apache.ibatis.plugin.Interceptor {
        final CountDownLatch started=new CountDownLatch(1);volatile boolean enabled;
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            var statement=(org.apache.ibatis.executor.statement.StatementHandler)invocation.getTarget();
            if(enabled&&statement.getBoundSql().getSql().contains("SELECT * FROM knowledge_document_index_execution WHERE lease_owner="))started.countDown();
            return invocation.proceed();
        }
    }
}
