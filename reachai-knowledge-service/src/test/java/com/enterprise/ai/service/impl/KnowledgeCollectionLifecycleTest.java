package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.KnowledgeBaseRequest;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.pipeline.document.job.*;
import com.enterprise.ai.pipeline.step.VectorStoreStep;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.retrieval.KnowledgeRetrievalEngine;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.enterprise.ai.vector.VectorService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeCollectionLifecycleTest {
    KnowledgeQueryTestDatabase db;
    KnowledgeServiceImpl service;
    VectorService vectors;
    TransactionTemplate tx;
    final Set<String> remote=new HashSet<>();

    @BeforeEach
    void setup()throws Exception {
        db=new KnowledgeQueryTestDatabase(List.of("knowledge_base","knowledge_file_info","knowledge_chunk","knowledge_document_import_job","knowledge_document_index_execution","knowledge_user_file_permission","knowledge_question","knowledge_tag","knowledge_collection_lifecycle"),
                KnowledgeCollectionLifecycleRepository.class, KnowledgeBaseRepository.class,FileInfoRepository.class,ChunkRepository.class,DocumentImportJobRepository.class,DocumentIndexExecutionRepository.class,UserFilePermissionRepository.class,KnowledgeQuestionRepository.class,KnowledgeTagRepository.class);
        vectors=mock(VectorService.class);
        doAnswer(call->{remote.add(call.getArgument(0));return null;}).when(vectors).ensureCollection(anyString(),anyInt());
        doAnswer(call->{remote.remove(call.getArgument(0));return null;}).when(vectors).dropCollection(anyString());
        doAnswer(call->{if(!remote.contains(call.getArgument(0)))throw new IllegalStateException("Collection missing");return null;}).when(vectors).upsert(anyString(),anyList(),anyList(),anyList(),anyList());
        tx=new TransactionTemplate(new DataSourceTransactionManager(db.jdbc().getDataSource()));
        var bases=db.mapper(KnowledgeBaseRepository.class);var artifacts=mock(DocumentArtifactStore.class);
        service=KnowledgeManagementTestServices.create(db.mapper(FileInfoRepository.class), db.mapper(ChunkRepository.class), db.mapper(KnowledgeTagRepository.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class), mock(KnowledgeOperationsQuery.class), KnowledgeIndexTestSupport.writer(db,mock(EmbeddingService.class),vectors), new KnowledgeBaseLookup(bases), mock(KnowledgeRetrievalEngine.class), KnowledgeIndexTestSupport.deletion(db,artifacts,db.mapper(UserFilePermissionRepository.class),db.mapper(KnowledgeQuestionRepository.class)), KnowledgeIndexTestSupport.baseLifecycle(db,vectors,KnowledgeIndexTestSupport.deletion(db,artifacts,db.mapper(UserFilePermissionRepository.class),db.mapper(KnowledgeQuestionRepository.class)),db.mapper(KnowledgeTagRepository.class),db.mapper(KnowledgeQuestionRepository.class)), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class));
    }
    @AfterEach void close(){if(db!=null)db.close();}

    @Test
    void collectionCreationPublishesMetadataOnlyAfterRemoteWorkOutsideTransactions() {
        doAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Collection creation must not hold a metadata transaction");
            assertEquals(0,count("knowledge_base"),"A collection still being created must not be published as a knowledge base");
            remote.add(call.getArgument(0));return null;
        }).when(vectors).ensureCollection(anyString(),anyInt());
        create();assertEquals(1,count("knowledge_base"));
    }

    @Test
    void anUnknownCreationResultRetainsADurableCleanupIntent() {
        doAnswer(call->{remote.add(call.getArgument(0));throw new IllegalStateException("Unknown remote create result");}).when(vectors).ensureCollection(anyString(),anyInt());
        assertThrows(IllegalStateException.class,this::create);
        assertEquals(0,count("knowledge_base"));assertEquals(1,remote.size());
        assertEquals(1,count("knowledge_collection_lifecycle"),"An unknown create must retain its physical identity after rollback");
        assertEquals("RECLAIMING",db.jdbc().queryForObject("SELECT state FROM knowledge_collection_lifecycle",String.class));
    }

    @Test
    void knowledgeBaseDeletionDoesNotDropTheRemoteCollectionInsideItsTransaction() {
        create();clearInvocations(vectors);
        tx.executeWithoutResult(status->service.deleteByCode("kb"));
        verifyNoInteractions(vectors);assertEquals(0,count("knowledge_base"));
    }

    @Test
    void rollingBackKnowledgeBaseDeletionPreservesTheRemoteCollection() {
        create();String collection=physical();
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{service.deleteByCode("kb");throw new IllegalStateException("rollback after deletion");}));
        assertEquals(1,count("knowledge_base"));
        assertTrue(remote.contains(collection),"Metadata rollback must not lose the published collection");
        assertEquals("READY",state(collection));
    }

    @Test
    void deletingTheKnowledgeBaseRevokesJobsAndRemovesItsOwnedAssociations() {
        create();long kb=db.jdbc().queryForObject("SELECT id FROM knowledge_base",Long.class);
        db.jdbc().update("INSERT INTO knowledge_file_info(file_id,knowledge_base_id,file_name) VALUES ('file',?,'原文件.txt')",kb);
        db.jdbc().update("INSERT INTO knowledge_chunk(file_id,knowledge_base_id,content,chunk_index,vector_id,collection_name) VALUES ('file',?,'保留边界测试',0,'legacy',?)",kb,physical());
        db.jdbc().update("INSERT INTO knowledge_user_file_permission(user_id,file_id) VALUES ('测试用户','file')");
        db.jdbc().update("INSERT INTO knowledge_question(knowledge_base_id,question) VALUES (?,'原问题')",kb);
        db.jdbc().update("INSERT INTO knowledge_tag(knowledge_base_id,target_type,target_id,tag_key,tag_value) VALUES (?,'FILE','file','分类','原标签')",kb);
        db.jdbc().update("INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,file_name,file_type,source_object_key,provider_type,status,stage) VALUES ('old-job','pending',?,'kb','任务.txt','txt','source','JAVA_FAST','QUEUED','QUEUED')",kb);
        tx.executeWithoutResult(status->service.deleteByCode("kb"));
        assertEquals("CANCELLED",db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job",String.class),"Knowledge-base deletion must revoke every pending import");
        for(String table:List.of("knowledge_base","knowledge_file_info","knowledge_chunk","knowledge_user_file_permission","knowledge_question","knowledge_tag"))assertEquals(0,count(table),table);
    }

    @Test
    void aLateRegisteredVectorWriteCannotRecreateADeletedCollection() {
        create();String collection=physical();long kb=db.jdbc().queryForObject("SELECT id FROM knowledge_base",Long.class);
        var store=spy(KnowledgeIndexTestSupport.executions(db));
        doAnswer(call->{Object result=call.callRealMethod();tx.executeWithoutResult(status->service.deleteByCode("kb"));remote.remove(collection);return result;})
                .when(store).register(any(),any());
        var context=new PipelineContext();context.setKnowledgeBaseId(kb);context.setKnowledgeBaseCode("kb");context.setVectorCollectionName(collection);context.setKnowledgeBaseDimension(2);
        context.setFileId("file");context.setIndexExecutionId(UUID.randomUUID().toString());context.setIndexOperation("DIRECT");context.setChunks(List.of("旧任务正文"));context.setVectors(List.of(List.of(1f,0f)));
        try{new VectorStoreStep(vectors,store).process(context);}catch(RuntimeException expected){/* A missing collection rejects upsert. */}
        assertFalse(remote.contains(collection),"A late indexer must not recreate the retired physical collection");
    }

    @Test
    void aFailedDropResumesFromThePersistedIdentityAfterReconstruction() {
        create();String collection=physical();service.deleteByCode("kb");
        doThrow(new IllegalStateException("internal detail must not be stored")).when(vectors).dropCollection(collection);
        reclaimer().reclaimPending();assertTrue(remote.contains(collection));assertEquals("RECLAIMING",state(collection));
        assertEquals("IllegalStateException",db.jdbc().queryForObject("SELECT last_cleanup_error FROM knowledge_collection_lifecycle",String.class));
        doAnswer(call->{remote.remove(call.getArgument(0));return null;}).when(vectors).dropCollection(collection);
        due();reclaimer().reclaimPending();assertFalse(remote.contains(collection));assertEquals("RECLAIMED",state(collection));
    }

    @Test
    void unknownCreationKeepsSweepingAfterAnEmptyDropAndALateRemoteCreate() {
        doAnswer(call->{remote.add(call.getArgument(0));throw new IllegalStateException("unknown creation");}).when(vectors).ensureCollection(anyString(),anyInt());
        assertThrows(IllegalStateException.class,this::create);String collection=remote.iterator().next();
        reclaimer().reclaimPending();assertFalse(remote.contains(collection));assertEquals("RECLAIMING",state(collection));
        remote.add(collection);due();reclaimer().reclaimPending();assertFalse(remote.contains(collection));assertEquals("RECLAIMING",state(collection));
        assertEquals(0,count("knowledge_base"));
    }

    @Test
    void aLateCreateAcknowledgementRevokesAnEarlierCleanupLeaseAndCannotPublish() {
        var oldClaim=new java.util.concurrent.atomic.AtomicReference<com.enterprise.ai.domain.entity.KnowledgeCollectionLifecycle>();
        doAnswer(call->{
            String collection=call.getArgument(0);
            db.jdbc().update("UPDATE knowledge_collection_lifecycle SET create_deadline='2000-01-01 00:00:00'");
            oldClaim.set(store().claim(db.mapper(KnowledgeCollectionLifecycleRepository.class).selectById(collection)));
            assertNotNull(oldClaim.get());remote.add(collection);return null;
        }).when(vectors).ensureCollection(anyString(),anyInt());
        assertThrows(IllegalStateException.class,this::create);String collection=oldClaim.get().getCollectionName();
        store().finish(oldClaim.get(),null);assertEquals("RECLAIMING",state(collection));
        assertNull(db.jdbc().queryForObject("SELECT cleanup_lease_owner FROM knowledge_collection_lifecycle",String.class));
        reclaimer().reclaimPending();assertEquals("RECLAIMED",state(collection));assertFalse(remote.contains(collection));assertEquals(0,count("knowledge_base"));
    }

    @Test
    void aMetadataInsertFailureRollsBackPublicationButPreservesConfirmedCleanup() {
        db.addInterceptor(new FailAfterBaseInsert());
        assertThrows(RuntimeException.class,this::create);assertEquals(0,count("knowledge_base"));
        String collection=remote.iterator().next();assertEquals("RECLAIMING",state(collection));
        assertEquals(1,db.jdbc().queryForObject("SELECT create_acknowledged FROM knowledge_collection_lifecycle",Integer.class));
        reclaimer().reclaimPending();assertEquals("RECLAIMED",state(collection));assertFalse(remote.contains(collection));
    }

    @Test
    void aCompetingCreateKeepsTheWinningKnowledgeBaseAndRetiresOnlyTheLoser() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();var winner=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(call->{String collection=call.getArgument(0);remote.add(collection);if(calls.incrementAndGet()==1){create();winner.set(physical());}return null;}).when(vectors).ensureCollection(anyString(),anyInt());
        assertThrows(org.springframework.dao.DuplicateKeyException.class,this::create);
        assertEquals(1,count("knowledge_base"));assertEquals(2,remote.size());
        reclaimer().reclaimPending();assertEquals(Set.of(winner.get()),remote);assertEquals("READY",state(winner.get()));
    }

    @Test
    void anExistingKnowledgeBasePreventsCollectionReclamation() {
        create();String collection=physical();
        db.jdbc().update("UPDATE knowledge_collection_lifecycle SET state='RECLAIMING'");
        reclaimer().reclaimPending();assertTrue(remote.contains(collection));assertEquals("RECLAIMING",state(collection));
        verify(vectors,never()).dropCollection(anyString());
    }

    @Test
    void aSurvivingChunkReferenceDefersCollectionDeletion() {
        create();String collection=physical();service.deleteByCode("kb");
        db.jdbc().update("INSERT INTO knowledge_chunk(file_id,knowledge_base_id,content,chunk_index,vector_id,collection_name) VALUES ('shared',99,'共享引用正文',0,'legacy',?)",collection);
        reclaimer().reclaimPending();assertTrue(remote.contains(collection));assertEquals("RECLAIMING",state(collection));
        db.jdbc().update("DELETE FROM knowledge_chunk WHERE knowledge_base_id=99");due();reclaimer().reclaimPending();
        assertFalse(remote.contains(collection));assertEquals("RECLAIMED",state(collection));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void aLegacyKnowledgeBaseDoesNotInventACreateAcknowledgementDuringDeletion(boolean unknownDimension) {
        create();String collection=physical();db.jdbc().update("DELETE FROM knowledge_collection_lifecycle");
        if(unknownDimension)db.jdbc().update("UPDATE knowledge_base SET dimension=NULL");
        service.deleteByCode("kb");reclaimer().reclaimPending();assertFalse(remote.contains(collection));
        assertEquals("RECLAIMING",state(collection));assertEquals(0,db.jdbc().queryForObject("SELECT create_acknowledged FROM knowledge_collection_lifecycle",Integer.class));
        assertEquals(unknownDimension?null:2,db.jdbc().queryForObject("SELECT dimension FROM knowledge_collection_lifecycle",Integer.class));
    }

    @Test
    void historicalJobsDoNotDeleteAFileIdNowOwnedByAnotherKnowledgeBase() {
        create();long retired=db.jdbc().queryForObject("SELECT id FROM knowledge_base WHERE code='kb'",Long.class);create("other");
        long current=db.jdbc().queryForObject("SELECT id FROM knowledge_base WHERE code='other'",Long.class);
        db.jdbc().update("INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,file_name,file_type,source_object_key,provider_type,status,stage) VALUES ('history','shared',?,'kb','旧任务.txt','txt','source','JAVA_FAST','COMPLETED','COMPLETED')",retired);
        db.jdbc().update("INSERT INTO knowledge_file_info(file_id,knowledge_base_id,file_name) VALUES ('shared',?,'其他知识库文件.txt')",current);
        db.jdbc().update("INSERT INTO knowledge_user_file_permission(user_id,file_id) VALUES ('other-user','shared')");
        service.deleteByCode("kb");reclaimer().reclaimPending();
        assertEquals("其他知识库文件.txt",db.jdbc().queryForObject("SELECT file_name FROM knowledge_file_info",String.class));
        assertEquals(1,count("knowledge_user_file_permission"));assertEquals("COMPLETED",db.jdbc().queryForObject("SELECT status FROM knowledge_document_import_job",String.class));
    }

    KnowledgeCollectionLifecycleStore store(){return KnowledgeIndexTestSupport.collectionStore(db);}
    KnowledgeCollectionReclaimer reclaimer(){return new KnowledgeCollectionReclaimer(db.mapper(KnowledgeCollectionLifecycleRepository.class),store(),vectors);}
    String state(String collection){return db.jdbc().queryForObject("SELECT state FROM knowledge_collection_lifecycle WHERE collection_name=?",String.class,collection);}
    void due(){db.jdbc().update("UPDATE knowledge_collection_lifecycle SET next_cleanup_at='2000-01-01 00:00:00' WHERE state='RECLAIMING'");}
    void create(){create("kb");}
    void create(String code){var request=new KnowledgeBaseRequest();request.setName("生命周期测试知识库");request.setCode(code);request.setDimension(2);request.setEmbeddingModelInstanceId("model");request.setLlmModelInstanceId("chat");tx.executeWithoutResult(status->service.create(request));}
    String physical(){return db.jdbc().queryForObject("SELECT vector_collection_name FROM knowledge_base",String.class);}
    int count(String table){return db.jdbc().queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}

    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="update",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class}))
    static class FailAfterBaseInsert implements org.apache.ibatis.plugin.Interceptor {
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation)throws Throwable {
            Object result=invocation.proceed();var statement=(org.apache.ibatis.mapping.MappedStatement)invocation.getArgs()[0];
            if(statement.getId().contains("KnowledgeBaseRepository.")&&statement.getSqlCommandType()==org.apache.ibatis.mapping.SqlCommandType.INSERT)
                throw new IllegalStateException("Injected failure after knowledge base insert");
            return result;
        }
    }
}
