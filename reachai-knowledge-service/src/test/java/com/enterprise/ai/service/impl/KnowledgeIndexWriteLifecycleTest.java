package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.KnowledgeImportRequest;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.retrieval.KnowledgeRetrievalEngine;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.enterprise.ai.vector.VectorService;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.document.job.*;
import com.enterprise.ai.pipeline.step.VectorStoreStep;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeIndexWriteLifecycleTest {
    private KnowledgeQueryTestDatabase db;
    private KnowledgeServiceImpl service;
    private EmbeddingService embedding;
    private VectorService vectors;
    private TransactionTemplate tx;
    private final Set<String> remote = ConcurrentHashMap.newKeySet();
    private final List<List<String>> writes = new CopyOnWriteArrayList<>();
    private Runnable afterUpsert = () -> {};

    @BeforeEach
    void setup() throws Exception {
        db = new KnowledgeQueryTestDatabase(List.of("knowledge_base","knowledge_file_info","knowledge_chunk","knowledge_document_import_job","knowledge_document_index_execution"),
                KnowledgeBaseRepository.class,FileInfoRepository.class,ChunkRepository.class,DocumentImportJobRepository.class,DocumentIndexExecutionRepository.class);
        db.jdbc().update("INSERT INTO knowledge_base(id,name,code,vector_collection_name,dimension,embedding_model_instance_id) VALUES (7,'生命周期知识库','kb','physical_kb',2,'model')");
        embedding = mock(EmbeddingService.class); vectors = mock(VectorService.class);
        when(embedding.embed(anyString(),anyString())).thenReturn(List.of(1f,0f));
        when(embedding.embedBatch(anyString(),anyList())).thenAnswer(call -> Collections.nCopies(call.<List<String>>getArgument(1).size(),List.of(1f,0f)));
        doAnswer(call -> { remote.remove(call.getArgument(1)); return null; }).when(vectors).deleteById(anyString(),anyString());
        doAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),"No transaction across vector I/O");
            var ids=List.copyOf(call.<List<String>>getArgument(1)); writes.add(ids); remote.addAll(ids); afterUpsert.run(); return null;
        })
                .when(vectors).upsert(anyString(),anyList(),anyList(),anyList(),anyList());
        var bases=db.mapper(KnowledgeBaseRepository.class);
        service=KnowledgeManagementTestServices.create(db.mapper(FileInfoRepository.class), db.mapper(ChunkRepository.class), mock(KnowledgeTagRepository.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class), mock(KnowledgeOperationsQuery.class), KnowledgeIndexTestSupport.writer(db, embedding, vectors), new KnowledgeBaseLookup(bases), mock(KnowledgeRetrievalEngine.class), KnowledgeIndexTestSupport.deletion(db), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeBaseLifecycleService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class));
        tx=new TransactionTemplate(new DataSourceTransactionManager(db.jdbc().getDataSource()));
    }

    @AfterEach
    void close() { if(db!=null)db.close(); }

    @Test
    void failedEmbeddingMustLeaveTheCurrentPublishedVectorAvailable() {
        seedChunk();
        when(embedding.embed("model","原始正文")).thenThrow(new IllegalStateException("model unavailable"));
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->service.reembedChunk(11L)));
        assertTrue(remote.contains("old_vector"),"A failed model call must preserve the published vector");
        assertEquals("old_vector",db.jdbc().queryForObject("SELECT vector_id FROM knowledge_chunk WHERE id=11",String.class));
    }

    @Test
    void successiveDirectImportsMustNeverReuseRemoteVectorIdentities() {
        var request=new KnowledgeImportRequest(); request.setKnowledgeBaseCode("kb"); request.setFileId("same_file");
        request.setFileName("重复文件.txt"); request.setChunks(List.of("第一次正文"));
        tx.executeWithoutResult(status->service.importChunks(request));
        db.jdbc().update("DELETE FROM knowledge_chunk"); db.jdbc().update("DELETE FROM knowledge_file_info");
        request.setChunks(List.of("第二次正文"));
        tx.executeWithoutResult(status->service.importChunks(request));
        assertTrue(Collections.disjoint(writes.get(0),writes.get(1)),"Each external write must have a new vector identity after file recreation");
        assertEquals("第二次正文",db.jdbc().queryForObject("SELECT content FROM knowledge_chunk",String.class));
    }

    @Test
    void anEmbeddingResultMustNotOverwriteContentCommittedDuringTheModelCall() {
        seedChunk();
        when(embedding.embed("model","原始正文")).thenAnswer(call -> {
            var executor=Executors.newSingleThreadExecutor();
            try { executor.submit(()->db.jdbc().update("UPDATE knowledge_chunk SET content='并发提交正文' WHERE id=11")).get(5,TimeUnit.SECONDS); }
            finally { executor.shutdownNow(); }
            return List.of(1f,0f);
        });
        try { tx.executeWithoutResult(status->service.reembedChunk(11L)); }
        catch (com.enterprise.ai.pipeline.PipelineException expected) { }
        assertEquals("并发提交正文",db.jdbc().queryForObject("SELECT content FROM knowledge_chunk WHERE id=11",String.class),
                "A delayed embedding must not restore the earlier chunk content");
        assertTrue(remote.contains("old_vector"));
    }

    @Test
    void replacementPublishesBeforeRetiringAnExactlyNamedLegacyVector() {
        seedChunk();
        when(embedding.embed("model","原始正文")).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(remote.contains("old_vector")); return List.of(1f,0f);
        });
        afterUpsert=()->assertEquals("old_vector",db.jdbc().queryForObject("SELECT vector_id FROM knowledge_chunk WHERE id=11",String.class));
        tx.executeWithoutResult(status->service.reembedChunk(11L));
        String published=vectorId();
        assertNotEquals("old_vector",published); assertTrue(remote.containsAll(List.of("old_vector",published)));
        assertEquals("old_vector",db.jdbc().queryForObject("SELECT single_vector_id FROM knowledge_document_index_execution WHERE operation_type='RETIRED_VECTOR'",String.class));
        assertEquals(0,db.jdbc().queryForObject("SELECT write_acknowledged FROM knowledge_document_index_execution WHERE operation_type='RETIRED_VECTOR'",Integer.class));
        reclaimer().reclaimPending();
        assertEquals(Set.of(published),remote);
        assertEquals("RECLAIMING",db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution WHERE operation_type='RETIRED_VECTOR'",String.class),
                "An unrecorded historical writer has no completion evidence");
        remote.add("old_vector"); makeDue(); reclaimer().reclaimPending();
        assertEquals(Set.of(published),remote);
    }

    @Test
    void aRetiredVectorWithConfirmedPublishedSourceCanFinishReclamation() {
        service.importChunks(request("file","原始正文"));
        Long id=db.jdbc().queryForObject("SELECT id FROM knowledge_chunk",Long.class);
        String old=vectorId(); service.reembedChunk(id); String current=vectorId();
        assertNotEquals(old,current);
        assertEquals(1,db.jdbc().queryForObject("SELECT write_acknowledged FROM knowledge_document_index_execution WHERE operation_type='RETIRED_VECTOR'",Integer.class));
        reclaimer().reclaimPending();
        assertEquals(Set.of(current),remote);
        assertEquals("RECLAIMED",db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution WHERE operation_type='RETIRED_VECTOR'",String.class));
    }

    @Test
    void retirementPreservesAnExactlyNamedVectorWhileAnotherLegacyChunkStillReferencesIt() {
        seedChunk();
        db.jdbc().update("INSERT INTO knowledge_file_info(file_id,knowledge_base_id,file_name) VALUES ('shared-file',7,'共享旧主键.txt')");
        db.jdbc().update("INSERT INTO knowledge_chunk(file_id,knowledge_base_id,content,vector_id,collection_name) VALUES ('shared-file',7,'历史引用','old_vector','physical_kb')");
        service.reembedChunk(11L); reclaimer().reclaimPending();
        assertTrue(remote.contains("old_vector"));
        verify(vectors,never()).deleteById("physical_kb","old_vector");
        db.jdbc().update("DELETE FROM knowledge_chunk WHERE file_id='shared-file'");
        makeDue(); reclaimer().reclaimPending();
        assertFalse(remote.contains("old_vector"));
        assertTrue(remote.contains(writes.get(0).get(0)));
    }

    @ParameterizedTest
    @ValueSource(strings={"content","file-generation","vector-reference"})
    void changesAfterTheRemoteWriteRejectPublicationAndOnlyReclaimTheNewAttempt(String change) {
        seedChunk();
        afterUpsert=()-> {
            switch(change) {
                case "content" -> db.jdbc().update("UPDATE knowledge_chunk SET content='新正文' WHERE id=11");
                case "file-generation" -> {
                    db.jdbc().update("DELETE FROM knowledge_file_info WHERE id=3");
                    db.jdbc().update("INSERT INTO knowledge_file_info(id,file_id,knowledge_base_id,file_name) VALUES (99,'file',7,'重建文件.txt')");
                }
                case "vector-reference" -> db.jdbc().update("UPDATE knowledge_chunk SET vector_id='another_vector' WHERE id=11");
            }
        };
        assertThrows(PipelineException.class,()->service.reembedChunk(11L));
        assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution WHERE state='PUBLISHED' OR operation_type='RETIRED_VECTOR'",Integer.class));
        reclaimer().reclaimPending();
        assertEquals(Set.of("old_vector"),remote);
        if(change.equals("content"))assertEquals("新正文",db.jdbc().queryForObject("SELECT content FROM knowledge_chunk",String.class));
        if(change.equals("file-generation"))assertEquals("重建文件.txt",db.jdbc().queryForObject("SELECT file_name FROM knowledge_file_info",String.class));
        if(change.equals("vector-reference"))assertEquals("another_vector",vectorId());
    }

    @Test
    void aLaterCompletedReplacementWinsWithoutTheDelayedWriterRestoringItsTarget() {
        seedChunk(); var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        afterUpsert=()-> {
            if(!first.getAndSet(false))return;
            var executor=Executors.newSingleThreadExecutor();
            try { executor.submit(()->service.reembedChunk(11L)).get(5,TimeUnit.SECONDS); }
            catch(Exception e) { throw new IllegalStateException(e); }
            finally { executor.shutdownNow(); }
        };
        assertThrows(PipelineException.class,()->service.reembedChunk(11L));
        assertEquals(writes.get(1).get(0),vectorId());
        reclaimer().reclaimPending();
        assertEquals(Set.of(writes.get(1).get(0)),remote);
    }

    @Test
    void publicationUpdatesOnlyVectorFieldsAndPreservesConcurrentPresentationAndHitChanges() {
        seedChunk();
        afterUpsert=()->db.jdbc().update("UPDATE knowledge_chunk SET title='最新标题',enabled=0,hit_count=23 WHERE id=11");
        service.reembedChunk(11L);
        var row=db.jdbc().queryForMap("SELECT title,enabled,hit_count,content FROM knowledge_chunk WHERE id=11");
        assertEquals("最新标题",row.get("title")); assertEquals(0,row.get("enabled")); assertEquals(23,row.get("hit_count"));
        assertEquals("原始正文",row.get("content")); assertNotEquals("old_vector",vectorId());
    }

    @ParameterizedTest
    @ValueSource(strings={"DIRECT","REEMBED"})
    void aFailedPublicationRollsBackReferencesAndRetirementButPreservesTheCleanupJournal(String operation) {
        if(operation.equals("REEMBED"))seedChunk();
        db.addInterceptor(new FailPublication());
        assertThrows(RuntimeException.class,()-> {
            if(operation.equals("REEMBED"))service.reembedChunk(11L);else service.importChunks(request("file","正文"));
        });
        assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution WHERE operation_type='RETIRED_VECTOR' OR state='PUBLISHED'",Integer.class));
        assertEquals(1,db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_document_index_execution WHERE state='RECLAIMING' AND write_acknowledged=1",Integer.class));
        reclaimer().reclaimPending();
        if(operation.equals("REEMBED")) {assertEquals("old_vector",vectorId());assertEquals(Set.of("old_vector"),remote);}
        else {assertTrue(remote.isEmpty());assertEquals(0,db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_file_info",Integer.class));}
    }

    @Test
    void anUnknownStandaloneWriteKeepsItsIdentityForFutureSweeps() {
        afterUpsert=()-> {throw new IllegalStateException("unknown remote result");};
        assertThrows(IllegalStateException.class,()->service.importChunks(request("file","正文")));
        assertEquals(0,db.jdbc().queryForObject("SELECT write_acknowledged FROM knowledge_document_index_execution",Integer.class));
        reclaimer().reclaimPending(); assertTrue(remote.isEmpty());
        assertEquals("RECLAIMING",db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution",String.class));
        remote.addAll(writes.get(0)); makeDue(); reclaimer().reclaimPending(); assertTrue(remote.isEmpty());
        assertEquals("RECLAIMING",db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution",String.class));
    }

    @ParameterizedTest
    @ValueSource(strings={"DIRECT","PIPELINE"})
    void standaloneWritesRemainPublishableUntilTheDatabaseDeadlineThenLosePublicationEligibility(String operation) {
        var context=new PipelineContext();context.setKnowledgeBaseId(7L);context.setKnowledgeBaseCode("kb");context.setVectorCollectionName("physical_kb");
        context.setFileId("file");context.setFileName("截止测试.txt");context.setChunks(List.of("正文"));context.setVectors(List.of(List.of(1f,0f)));
        context.setIndexExecutionId(UUID.randomUUID().toString());context.setIndexOperation(operation);
        var store=KnowledgeIndexTestSupport.executions(db);var step=new VectorStoreStep(vectors,store);step.process(context);
        assertThrows(PipelineException.class,()->step.process(context)); assertEquals(1,writes.size());
        assertTrue(db.mapper(DocumentIndexExecutionRepository.class).findReclaimable(8).isEmpty());
        db.jdbc().update("UPDATE knowledge_document_index_execution SET publication_deadline='2000-01-01 00:00:00'");
        var metadata=KnowledgeIndexTestSupport.metadata(db,store);
        assertThrows(PipelineException.class,()->tx.executeWithoutResult(status->metadata.process(context)));
        reclaimer().reclaimPending();assertTrue(remote.isEmpty());
        assertEquals("RECLAIMED",db.jdbc().queryForObject("SELECT state FROM knowledge_document_index_execution",String.class));
        assertFalse(context.isImportPublished());
    }

    private String vectorId() {return db.jdbc().queryForObject("SELECT vector_id FROM knowledge_chunk",String.class);}

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void chunkEditsCannotRestoreAVectorReferenceObservedBeforeReplacement(boolean toggle) {
        seedChunk();
        db.addInterceptor(new AfterChunkRead(()-> {
            var executor=Executors.newSingleThreadExecutor();
            try { executor.submit(()->service.reembedChunk(11L)).get(5,TimeUnit.SECONDS); }
            catch(Exception e) {throw new IllegalStateException(e);}
            finally {executor.shutdownNow();}
        }));
        tx.executeWithoutResult(status-> {
            if(toggle) service.toggleChunk(11L,0);
            else {
                var request=new com.enterprise.ai.domain.dto.ChunkUpdateRequest();request.setTitle("人工标题");
                service.updateChunk(11L,request);
            }
        });
        assertEquals(writes.get(0).get(0),vectorId(),"A delayed editor must not restore the retired vector reference");
        if(toggle)assertEquals(0,db.jdbc().queryForObject("SELECT enabled FROM knowledge_chunk",Integer.class));
        else assertEquals("人工标题",db.jdbc().queryForObject("SELECT title FROM knowledge_chunk",String.class));
    }

    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,method="query",args={org.apache.ibatis.mapping.MappedStatement.class,Object.class,org.apache.ibatis.session.RowBounds.class,org.apache.ibatis.session.ResultHandler.class}))
    static class AfterChunkRead implements org.apache.ibatis.plugin.Interceptor {
        private final Runnable action;
        private final java.util.concurrent.atomic.AtomicBoolean first=new java.util.concurrent.atomic.AtomicBoolean(true);
        AfterChunkRead(Runnable action) {this.action=action;}
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            Object result=invocation.proceed();
            var statement=(org.apache.ibatis.mapping.MappedStatement)invocation.getArgs()[0];
            if(statement.getId().endsWith("ChunkRepository.selectById") && first.getAndSet(false))action.run();
            return result;
        }
    }
    private void makeDue() {db.jdbc().update("UPDATE knowledge_document_index_execution SET next_cleanup_at='2000-01-01 00:00:00' WHERE state='RECLAIMING'");}
    private DocumentIndexReclaimer reclaimer() {return new DocumentIndexReclaimer(db.mapper(DocumentIndexExecutionRepository.class),KnowledgeIndexTestSupport.executions(db),vectors,new DocumentImportJobProperties());}
    private KnowledgeImportRequest request(String file,String text) {
        var request=new KnowledgeImportRequest();request.setKnowledgeBaseCode("kb");request.setFileId(file);request.setFileName("测试文件.txt");request.setChunks(List.of(text));return request;
    }

    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.statement.StatementHandler.class,method="prepare",args={java.sql.Connection.class,Integer.class}))
    static class FailPublication implements org.apache.ibatis.plugin.Interceptor {
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            var statement=(org.apache.ibatis.executor.statement.StatementHandler)invocation.getTarget();
            if(statement.getBoundSql().getSql().contains("SET state='PUBLISHED'"))throw new IllegalStateException("Injected final publication failure");
            return invocation.proceed();
        }
    }

    private void seedChunk() {
        db.jdbc().update("INSERT INTO knowledge_file_info(id,file_id,knowledge_base_id,file_name,record_generation) VALUES (3,'file',7,'原始文件.txt',REPEAT('a',32))");
        db.jdbc().update("INSERT INTO knowledge_chunk(id,file_id,knowledge_base_id,content,chunk_index,vector_id,collection_name) VALUES (11,'file',7,'原始正文',0,'old_vector','physical_kb')");
        remote.add("old_vector");
    }
}
