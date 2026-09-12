package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.KnowledgeBaseRequest;
import com.enterprise.ai.domain.dto.KnowledgeImportRequest;
import com.enterprise.ai.pipeline.*;
import com.enterprise.ai.pipeline.step.MetadataPersistStep;
import com.enterprise.ai.pipeline.step.VectorStoreStep;
import com.enterprise.ai.pipeline.document.job.DocumentImportPublicationGuard;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.retrieval.KnowledgeRetrievalEngine;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.enterprise.ai.vector.VectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeCollectionIdentityTest {
    private KnowledgeQueryTestDatabase db;
    private KnowledgeServiceImpl service;
    private VectorService vectors;
    private EmbeddingService embedding;
    private TransactionTemplate transactions;
    private final Set<String> remoteCollections = new HashSet<>();
    private final List<String> createdCollections = new ArrayList<>();
    private final List<Runnable> delayedDrops = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        db = new KnowledgeQueryTestDatabase(List.of("knowledge_base", "knowledge_file_info", "knowledge_chunk", "knowledge_document_import_job", "knowledge_document_index_execution", "knowledge_collection_lifecycle"),
                KnowledgeCollectionLifecycleRepository.class, KnowledgeBaseRepository.class, FileInfoRepository.class, ChunkRepository.class, DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class);
        var bases = db.mapper(KnowledgeBaseRepository.class);
        vectors = mock(VectorService.class);
        doAnswer(call -> {
            String name = call.getArgument(0);
            remoteCollections.add(name); createdCollections.add(name);
            return null;
        }).when(vectors).ensureCollection(anyString(), anyInt());
        embedding = mock(EmbeddingService.class);
        when(embedding.embedBatch(anyString(), anyList())).thenAnswer(call -> Collections.nCopies(((List<?>) call.getArgument(1)).size(), List.of(1.0f, 0.0f)));
        when(embedding.embed(anyString(), anyString())).thenReturn(List.of(1.0f, 0.0f));
        service = KnowledgeManagementTestServices.create(db.mapper(FileInfoRepository.class), db.mapper(ChunkRepository.class), mock(KnowledgeTagRepository.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class), mock(KnowledgeOperationsQuery.class), KnowledgeIndexTestSupport.writer(db, embedding, vectors), new KnowledgeBaseLookup(bases), mock(KnowledgeRetrievalEngine.class), KnowledgeIndexTestSupport.deletion(db), KnowledgeIndexTestSupport.baseLifecycle(db,vectors,KnowledgeIndexTestSupport.deletion(db),mock(KnowledgeTagRepository.class),mock(KnowledgeQuestionRepository.class)), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(db.jdbc().getDataSource()));
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void creatingKnowledgeBaseUsesAnIndependentPhysicalIdentity() {
        create();
        assertEquals("中文知识库", db.jdbc().queryForObject("SELECT name FROM knowledge_base", String.class));
        assertNotEquals("reused-code", createdCollections.get(0), "Business code must not identify a physical collection");
        assertTrue(createdCollections.get(0).matches("reachai_kb_[0-9a-f]{32}"));
    }

    @Test
    void delayedOldDropCannotDeleteARecreatedKnowledgeBaseWithTheSameCode() {
        create();
        Long oldId = db.jdbc().queryForObject("SELECT id FROM knowledge_base", Long.class);
        doAnswer(call -> {
            String oldCollection = call.getArgument(0);
            delayedDrops.add(() -> remoteCollections.remove(oldCollection));
            throw new IllegalStateException("Injected unknown remote deletion result");
        }).when(vectors).dropCollection(anyString());
        transactions.executeWithoutResult(status -> service.deleteByCode("reused-code"));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_base", Integer.class));
        new KnowledgeCollectionReclaimer(db.mapper(KnowledgeCollectionLifecycleRepository.class),KnowledgeIndexTestSupport.collectionStore(db),vectors).reclaimPending();
        assertEquals(1,delayedDrops.size());
        create();
        assertNotEquals(oldId, db.jdbc().queryForObject("SELECT id FROM knowledge_base", Long.class));
        assertEquals("中文知识库", db.jdbc().queryForObject("SELECT name FROM knowledge_base", String.class));
        delayedDrops.forEach(Runnable::run);
        assertTrue(remoteCollections.contains(createdCollections.get(1)), "Late deletion of the old object must preserve the new collection");
        assertFalse(remoteCollections.contains(createdCollections.get(0)));
    }

    private void create() {
        var request = new KnowledgeBaseRequest();
        request.setCode("reused-code"); request.setName("中文知识库"); request.setDimension(2);
        request.setEmbeddingModelInstanceId("fixture-embedding"); request.setLlmModelInstanceId("fixture-chat");
        transactions.executeWithoutResult(status -> service.create(request));
    }

    @Test
    void pipelineResolvesPhysicalTargetFromDatabaseAndPublishesThatSameIdentity() {
        create();
        var bases = db.mapper(KnowledgeBaseRepository.class);
        var factory = mock(PipelineFactory.class);
        var pipeline = new KnowledgeImportPipeline("reused-code");
        var executions = KnowledgeIndexTestSupport.executions(db);
        pipeline.addStep(new VectorStoreStep(vectors, executions));
        var metadata = KnowledgeIndexTestSupport.metadata(db, executions);
        pipeline.addStep(new PipelineStep() {
            public String getName() { return "METADATA_PERSIST"; }
            public void process(PipelineContext context) { transactions.executeWithoutResult(status -> metadata.process(context)); }
        });
        when(factory.create("reused-code")).thenReturn(pipeline);
        var context = new PipelineContext(); context.setKnowledgeBaseCode("reused-code");
        context.setVectorCollectionName("untrusted-target"); context.setFileId("file"); context.setFileName("中文文件.txt");
        context.setChunks(List.of("中文正文")); context.setVectors(List.of(List.of(1.0f, 0.0f)));
        var result = new PipelineImportServiceImpl(factory, new KnowledgeBaseLookup(bases), KnowledgeIndexTestSupport.executions(db)).execute(context);
        assertEquals("SUCCESS", result.getStatus());
        assertEquals("reused-code", result.getKnowledgeBaseCode());
        assertEquals(createdCollections.get(0), context.getVectorCollectionName());
        assertEquals(createdCollections.get(0), db.jdbc().queryForObject("SELECT collection_name FROM knowledge_chunk", String.class));
        assertEquals("中文正文", db.jdbc().queryForObject("SELECT content FROM knowledge_chunk", String.class));
        assertEquals("中文文件.txt", db.jdbc().queryForObject("SELECT file_name FROM knowledge_file_info", String.class));
        verify(vectors).upsert(eq(createdCollections.get(0)), anyList(), anyList(), anyList(), anyList());
        verify(vectors, never()).ensureCollection(eq("reused-code"), anyInt());
    }

    @Test
    void oldJobAndOldPublicationCannotResolveAReusedBusinessCodeToANewKnowledgeBase() {
        create();
        Long oldId = db.jdbc().queryForObject("SELECT id FROM knowledge_base", Long.class);
        String oldCollection = createdCollections.get(0);
        transactions.executeWithoutResult(status -> service.deleteByCode("reused-code"));
        create();
        var context = new PipelineContext(); context.setKnowledgeBaseCode("reused-code"); context.setKnowledgeBaseId(oldId);
        context.setVectorCollectionName(oldCollection); context.setImportJobId("old-job"); context.setFileId("old-file");
        var factory = mock(PipelineFactory.class);
        var bases = db.mapper(KnowledgeBaseRepository.class);
        var result = new PipelineImportServiceImpl(factory, new KnowledgeBaseLookup(bases), KnowledgeIndexTestSupport.executions(db)).execute(context);
        assertEquals("FAILED", result.getStatus());
        verifyNoInteractions(factory);
        var metadata = new MetadataPersistStep(bases, db.mapper(FileInfoRepository.class), db.mapper(ChunkRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), mock(DocumentImportPublicationGuard.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class));
        assertThrows(PipelineException.class, () -> transactions.executeWithoutResult(status -> metadata.process(context)));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class));
        assertEquals(createdCollections.get(1), bases.selectOne(null).getVectorCollectionName());
    }

    @Test
    void generalEntityUpdatesCannotChangeThePhysicalCollection() {
        create();
        var bases = db.mapper(KnowledgeBaseRepository.class);
        var base = bases.selectOne(null);
        base.setVectorCollectionName("replacement-physical-identity");
        base.setName("修改后的中文名");
        transactions.executeWithoutResult(status -> bases.updateById(base));
        assertEquals("修改后的中文名", bases.selectById(base.getId()).getName());
        assertEquals(createdCollections.get(0), bases.selectById(base.getId()).getVectorCollectionName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"import", "reembed", "delete-by-code", "delete-file"})
    void fileCommandsUseTheOwningPhysicalCollection(String operation) {
        create();
        Long id = db.jdbc().queryForObject("SELECT id FROM knowledge_base", Long.class);
        String collection = createdCollections.get(0);
        db.jdbc().update("INSERT INTO knowledge_file_info(file_id,knowledge_base_id,file_name,import_job_id,record_generation) VALUES ('file',?,'file.txt','job',REPEAT('a',32))", id);
        db.jdbc().update("INSERT INTO knowledge_chunk(id,file_id,knowledge_base_id,content,chunk_index,vector_id,collection_name) VALUES (11,'file',?,'text',0,'vector',?)", id, collection);
        clearInvocations(vectors);
        transactions.executeWithoutResult(status -> {
            switch (operation) {
                case "import" -> {
                    var request = new KnowledgeImportRequest(); request.setKnowledgeBaseCode("reused-code");
                    request.setFileId("new-file"); request.setFileName("new.txt"); request.setChunks(List.of("new text"));
                    service.importChunks(request);
                    assertEquals(collection, db.jdbc().queryForObject("SELECT collection_name FROM knowledge_chunk WHERE file_id='new-file'", String.class));
                }
                case "reembed" -> service.reembedChunk(11L);
                case "delete-by-code" -> service.deleteByFileId("reused-code", "file");
                case "delete-file" -> service.deleteFileById("file");
                default -> throw new IllegalArgumentException(operation);
            }
        });
        if(operation.startsWith("delete")) new com.enterprise.ai.pipeline.document.job.DocumentIndexReclaimer(
                db.mapper(DocumentIndexExecutionRepository.class), KnowledgeIndexTestSupport.executions(db), vectors,
                new com.enterprise.ai.pipeline.document.job.DocumentImportJobProperties()).reclaimPending();
        var calls = mockingDetails(vectors).getInvocations();
        assertFalse(calls.isEmpty());
        for (var call : calls) assertEquals(collection, call.getArgument(0));
    }
}
