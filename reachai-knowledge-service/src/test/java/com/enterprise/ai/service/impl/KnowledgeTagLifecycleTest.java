package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.*;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.pipeline.document.job.DocumentImportPublicationGuard;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.pipeline.step.MetadataPersistStep;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.retrieval.KnowledgeRetrievalEngine;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeTagLifecycleTest {
    private KnowledgeQueryTestDatabase db;
    private DataSourceTransactionManager manager;
    private KnowledgeServiceImpl service;
    private KnowledgeFileDeletionService deletion;
    private KnowledgeTagService tags;

    @BeforeEach
    void setUp() throws Exception {
        db = new KnowledgeQueryTestDatabase(List.of("knowledge_base", "knowledge_file_info", "knowledge_chunk",
                "knowledge_tag", "knowledge_document_import_job", "knowledge_document_index_execution",
                "knowledge_user_file_permission", "knowledge_question"), KnowledgeBaseRepository.class,
                FileInfoRepository.class, ChunkRepository.class, KnowledgeTagRepository.class,
                DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class,
                UserFilePermissionRepository.class, KnowledgeQuestionRepository.class);
        manager = new DataSourceTransactionManager(db.jdbc().getDataSource());
        tags = new KnowledgeTagService(db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class),
                db.mapper(ChunkRepository.class), db.mapper(KnowledgeTagRepository.class), manager);
        db.jdbc().update("INSERT INTO knowledge_base(id,code,name,vector_collection_name) VALUES (7,'kb','目标知识库','physical'),(8,'other','其他知识库','other_physical')");
        db.jdbc().update("INSERT INTO knowledge_file_info(id,file_id,knowledge_base_id,file_name) VALUES (11,'file-1',7,'原文件'),(12,'keep',7,'保留文件'),(13,'foreign',8,'其他文件')");
        db.jdbc().update("INSERT INTO knowledge_chunk(id,file_id,knowledge_base_id,chunk_index,content) VALUES (101,'file-1',7,0,'原片段'),(102,'keep',7,0,'保留片段'),(201,'foreign',8,0,'其他片段')");
        deletion = new KnowledgeFileDeletionService(db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class),
                db.mapper(ChunkRepository.class), db.mapper(DocumentImportJobRepository.class), db.mapper(DocumentIndexExecutionRepository.class),
                mock(DocumentIndexExecutionStore.class), mock(DocumentArtifactStore.class), db.mapper(UserFilePermissionRepository.class), new com.enterprise.ai.service.impl.KnowledgeQuestionService(db.mapper(KnowledgeBaseRepository.class), db.mapper(ChunkRepository.class), db.mapper(KnowledgeQuestionRepository.class), manager), manager, tags);
        service = KnowledgeManagementTestServices.create(db.mapper(FileInfoRepository.class),
                db.mapper(ChunkRepository.class), db.mapper(KnowledgeTagRepository.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class),
                mock(KnowledgeOperationsQuery.class), mock(KnowledgeIndexWriteService.class),
                new KnowledgeBaseLookup(db.mapper(KnowledgeBaseRepository.class)), mock(KnowledgeRetrievalEngine.class),
                deletion, mock(KnowledgeBaseLifecycleService.class), tags);
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void deletingAFileRetiresItsOwnFileAndChunkTags() {
        seedTags();
        deletion.deleteByFileId("kb", "file-1");
        assertEquals(List.of(3L, 4L), tagIds(), "File retirement must remove its FILE and CHUNK tag references");
    }

    @Test
    void aSingleTagCannotPointToAMissingFile() {
        var request = request("FILE", "missing");
        assertThrows(IllegalArgumentException.class, () -> create(request), "A tag target must exist in its owning knowledge base");
        assertEquals(0, tagIds().size());
    }

    @Test
    void aParentTagCannotComeFromAnotherKnowledgeBase() {
        db.jdbc().update("INSERT INTO knowledge_tag(id,knowledge_base_id,tag_key,tag_value) VALUES (9,8,'外部','父标签')");
        var request = request("FILE", "file-1"); request.setParentId(9L);
        assertThrows(IllegalArgumentException.class, () -> create(request), "Parent tags must share the target knowledge base");
    }

    @Test
    void batchChunkTargetsUseTheResolvedNumericIdentity() {
        var request = new KnowledgeTagBatchRequest(); request.setTargetType("CHUNK");
        request.setTargetIds(List.of("00101")); request.setTagKey("主题"); request.setTagValue("合同");
        var tags = new TransactionTemplate(manager).execute(status -> service.batchCreateTags("kb", request));
        assertEquals("101", tags.get(0).getTargetId(), "Chunk tags must store the resolved canonical identity");
    }

    @Test
    void deletingAParentPreservesTheChildAndDetachesItsReference() {
        db.jdbc().update("INSERT INTO knowledge_tag(id,knowledge_base_id,tag_key,tag_value,parent_id) VALUES (1,7,'分类','父',NULL),(2,7,'分类','子',1)");
        new TransactionTemplate(manager).executeWithoutResult(status -> service.deleteTag("kb", 1L));
        assertEquals(List.of(2L), tagIds());
        assertNull(db.jdbc().queryForObject("SELECT parent_id FROM knowledge_tag WHERE id=2", Long.class),
                "Removing a parent must not leave a dangling child reference");
    }

    @Test
    void replacingAnExistingImportAttemptRetiresTheOldTagTargets() {
        seedTags();
        db.jdbc().update("UPDATE knowledge_file_info SET import_job_id='same-job' WHERE file_id='file-1'");
        var metadata = new MetadataPersistStep(db.mapper(KnowledgeBaseRepository.class), db.mapper(FileInfoRepository.class),
                db.mapper(ChunkRepository.class), new ObjectMapper(), mock(DocumentImportPublicationGuard.class), tags, org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class));
        var context = new PipelineContext(); context.setKnowledgeBaseId(7L); context.setKnowledgeBaseCode("kb");
        context.setVectorCollectionName("physical"); context.setFileId("file-1"); context.setFileName("新的文件");
        context.setImportJobId("same-job"); context.setChunks(List.of("新的片段")); context.setVectorIds(List.of("new-vector"));
        new TransactionTemplate(manager).executeWithoutResult(status -> metadata.process(context));
        assertEquals(List.of(3L, 4L), tagIds(), "Replaced file and chunk rows must retire their old tag references");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"FILE,foreign", "CHUNK,201", "CHUNK,999", "CHUNK,0",
            "CHUNK,-1", "CHUNK,9223372036854775808", "UNSUPPORTED,file-1"})
    void invalidTargetsNeverCreateATag(String type, String target) {
        assertThrows(IllegalArgumentException.class, () -> create(request(type, target)));
        assertTrue(tagIds().isEmpty());
    }

    @Test
    void aliasesAndRepeatedSingleOrBatchRequestsKeepOneAssociation() {
        var first = create(request(" chunk ", "00101"));
        var second = create(request("CHUNK", "101"));
        assertEquals(first.getId(), second.getId());
        var request = new KnowledgeTagBatchRequest(); request.setTargetType("chunk");
        request.setTargetIds(List.of("00101", "101", "00102")); request.setTagKey("主题"); request.setTagValue("合同");
        var added = service.batchCreateTags("kb", request);
        assertEquals(1, added.size()); assertEquals("102", added.get(0).getTargetId());
        assertEquals(2, tagIds().size());
    }

    @Test
    void deletingAFilePromotesSurvivingChildrenAndRollbackRestoresTheTree() {
        seedTags();
        db.jdbc().update("UPDATE knowledge_tag SET parent_id=1 WHERE id=3");
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            deletion.deleteByFileId("kb", "file-1");
            assertNull(db.jdbc().queryForObject("SELECT parent_id FROM knowledge_tag WHERE id=3", Long.class));
            status.setRollbackOnly();
        });
        assertEquals(List.of(1L, 2L, 3L, 4L), tagIds());
        assertEquals(1L, db.jdbc().queryForObject("SELECT parent_id FROM knowledge_tag WHERE id=3", Long.class));
        deletion.deleteByFileId("kb", "file-1");
        assertNull(db.jdbc().queryForObject("SELECT parent_id FROM knowledge_tag WHERE id=3", Long.class));
        assertEquals(List.of(3L, 4L), tagIds());
    }

    @Test
    void failureAfterTagDeletionRollsBackTagsChildrenAndFileMetadata() {
        seedTags();
        db.jdbc().update("UPDATE knowledge_tag SET parent_id=1 WHERE id=3");
        db.addInterceptor(new FailTagMutation(".delete", 1));
        assertThrows(RuntimeException.class, () -> deletion.deleteByFileId("kb", "file-1"));
        assertEquals(List.of(1L, 2L, 3L, 4L), tagIds());
        assertEquals(1L, db.jdbc().queryForObject("SELECT parent_id FROM knowledge_tag WHERE id=3", Long.class));
        assertEquals(3, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
        assertEquals(3, db.jdbc().queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class));
    }

    @Test
    void aBatchInsertFailureRollsBackEveryEarlierAssociation() {
        db.addInterceptor(new FailTagMutation(".insert", 2));
        var request = new KnowledgeTagBatchRequest(); request.setTargetType("FILE");
        request.setTargetIds(List.of("file-1", "keep")); request.setTagKey("主题"); request.setTagValue("合同");
        assertThrows(RuntimeException.class, () -> service.batchCreateTags("kb", request));
        assertTrue(tagIds().isEmpty());
    }

    @Test
    void aBatchWithAForeignTargetDoesNotPartiallyPublish() {
        var request = new KnowledgeTagBatchRequest(); request.setTargetType("FILE");
        request.setTargetIds(List.of("file-1", "foreign")); request.setTagKey("主题"); request.setTagValue("合同");
        assertThrows(IllegalArgumentException.class, () -> service.batchCreateTags("kb", request));
        assertTrue(tagIds().isEmpty());
    }

    @Test
    void fileRetirementRequiresTheMetadataTransaction() {
        seedTags();
        assertThrows(IllegalStateException.class, () -> tags.retireFile(7L, "file-1"));
        assertEquals(4, tagIds().size());
    }

    @Test
    void listAndStatisticsKeepChineseMetadataAndSeparateTargetCounts() {
        create(request("KNOWLEDGE", null)); create(request("FILE", "file-1")); create(request("CHUNK", "101"));
        var stats = service.listTagStats("kb");
        assertEquals(1, stats.size()); assertEquals("主题", stats.get(0).getTagKey());
        assertEquals("合同", stats.get(0).getTagValue()); assertEquals(3, stats.get(0).getTotalCount());
        assertEquals(1, stats.get(0).getFileCount()); assertEquals(1, stats.get(0).getChunkCount());
        assertEquals(1, stats.get(0).getKnowledgeCount());
        assertEquals("101", service.listTags("kb", "chunk", "00101").get(0).getTargetId());
        assertEquals(0, service.listTags("other", null, null).size());
    }

    @Test
    void knowledgeTargetsResolveToTheirOwnerAndRejectForeignAliases() {
        var first = create(request("KNOWLEDGE", "kb"));
        var second = create(request("KNOWLEDGE", "7"));
        assertNull(first.getTargetId()); assertEquals(first.getId(), second.getId());
        assertEquals(first.getId(), service.listTags("kb", "KNOWLEDGE", "7").get(0).getId());
        assertThrows(IllegalArgumentException.class, () -> create(request("KNOWLEDGE", "other")));
    }

    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(type=org.apache.ibatis.executor.Executor.class,
            method="update", args={org.apache.ibatis.mapping.MappedStatement.class,Object.class}))
    static final class FailTagMutation implements org.apache.ibatis.plugin.Interceptor {
        private final String operation; private final int failAt; private int calls;
        FailTagMutation(String operation, int failAt) { this.operation = operation; this.failAt = failAt; }
        @Override public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            var statement = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
            Object result = invocation.proceed();
            if (statement.getId().equals(KnowledgeTagRepository.class.getName() + operation) && ++calls == failAt) {
                throw new IllegalStateException("controlled failure after real tag mutation");
            }
            return result;
        }
    }

    private KnowledgeTagDTO create(KnowledgeTagRequest request) {
        return new TransactionTemplate(manager).execute(status -> service.createTag("kb", request));
    }

    private KnowledgeTagRequest request(String type, String target) {
        var request = new KnowledgeTagRequest(); request.setTargetType(type); request.setTargetId(target);
        request.setTagKey("主题"); request.setTagValue("合同"); return request;
    }

    private void seedTags() {
        db.jdbc().update("INSERT INTO knowledge_tag(id,knowledge_base_id,target_type,target_id,tag_key,tag_value) VALUES "
                + "(1,7,'FILE','file-1','主题','原件'),(2,7,'CHUNK','101','主题','原片段'),"
                + "(3,7,'FILE','keep','主题','保留'),(4,8,'CHUNK','201','主题','其他知识库')");
    }

    private List<Long> tagIds() { return db.jdbc().queryForList("SELECT id FROM knowledge_tag ORDER BY id", Long.class); }
}
