package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.dto.ChunkUpdateRequest;
import com.enterprise.ai.domain.dto.ChunkVO;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.retrieval.KnowledgeRetrievalEngine;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class KnowledgeContentManagementTest {
    private KnowledgeQueryTestDatabase db;
    private KnowledgeServiceImpl service;
    private TransactionTemplate transaction;
    private KnowledgePublicationMysqlDatabase mysql;

    protected boolean useMysql() { return false; }

    @BeforeEach
    void setup() throws Exception {
        if (useMysql()) {
            mysql = KnowledgePublicationMysqlDatabase.withContentManagement(10);
            try (var connection = mysql.getConnection()) {
                assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
            }
            db = new KnowledgeQueryTestDatabase(mysql,
                    KnowledgeBaseRepository.class, FileInfoRepository.class, ChunkRepository.class, KnowledgeTagRepository.class);
        } else {
            db = new KnowledgeQueryTestDatabase(List.of("knowledge_base", "knowledge_file_info", "knowledge_chunk", "knowledge_tag"),
                    KnowledgeBaseRepository.class, FileInfoRepository.class, ChunkRepository.class, KnowledgeTagRepository.class);
        }
        db.jdbc().update("INSERT INTO knowledge_base(id,name,code,vector_collection_name) VALUES (7,'知识库','kb','physical_kb'),(8,'其他库','other','physical_other')");
        db.jdbc().update("INSERT INTO knowledge_file_info(file_id,knowledge_base_id,file_name) VALUES ('file',7,'资料.txt'),('other-file',8,'其他.txt')");
        db.jdbc().update("INSERT INTO knowledge_chunk(id,file_id,knowledge_base_id,content,title,chunk_index,hit_count,enabled,vector_id,collection_name) VALUES "
                + "(11,'file',7,'匹配正文','原标题',1,3,1,'vector-11','physical_kb'),"
                + "(12,'file',7,'其他正文','匹配标题',0,8,0,'vector-12','physical_kb'),"
                + "(13,'file',7,'第三正文','第三标题',2,1,1,'vector-13','physical_kb'),"
                + "(21,'other-file',8,'匹配其他库','其他标题',0,99,1,'vector-21','physical_other')");
        service = KnowledgeManagementTestServices.create(db.mapper(FileInfoRepository.class), db.mapper(ChunkRepository.class), db.mapper(KnowledgeTagRepository.class),
                mock(KnowledgeQuestionService.class), mock(KnowledgeOperationsQuery.class), mock(KnowledgeIndexWriteService.class),
                new KnowledgeBaseLookup(db.mapper(KnowledgeBaseRepository.class)), mock(KnowledgeRetrievalEngine.class),
                mock(KnowledgeFileDeletionService.class), mock(KnowledgeBaseLifecycleService.class), mock(KnowledgeTagService.class));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(db.jdbc().getDataSource()));
    }

    @AfterEach
    void cleanup() {
        try { if (db != null) db.close(); }
        finally { if (mysql != null) mysql.close(); }
    }

    @Test
    void fileQueriesKeepOwnerOrderAndContentLength() {
        assertEquals(List.of("file"), service.getFilesByKbCode("kb").stream().map(file -> file.getFileId()).toList());
        var chunks = service.getChunksByFileId("file");
        assertEquals(List.of(12L, 11L, 13L), chunks.stream().map(ChunkVO::getId).toList());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getLength() == chunk.getContent().length()));
    }

    @Test
    void chunkSearchKeepsKeywordEnabledRankingAndMinimumLimit() {
        assertEquals(List.of(12L, 11L), ids(service.listChunks("kb", "匹配", null, null, null, 20)));
        assertEquals(List.of(11L), ids(service.listChunks("kb", "匹配", 1, null, null, 20)));
        assertEquals(List.of(12L), ids(service.listChunks("kb", null, null, null, null, 0)));
    }

    @Test
    void tagSearchIgnoresInvalidTargetNumbersAndOtherOwners() {
        db.jdbc().update("INSERT INTO knowledge_tag(knowledge_base_id,target_type,target_id,tag_key,tag_value) VALUES "
                + "(7,'CHUNK','0011','类别','保留'),(7,'CHUNK','11x','类别','保留'),"
                + "(7,'CHUNK','9223372036854775808','类别','保留'),(8,'CHUNK','12','类别','保留'),"
                + "(7,'FILE','13','类别','保留')");
        assertEquals(List.of(11L), ids(service.listChunks("kb", null, null, "类别", "保留", 20)));
        assertTrue(service.listChunks("kb", null, null, "不存在", null, 20).isEmpty());
    }

    @Test
    void titleOnlyAndEmptyPatchKeepVectorAndOtherFields() {
        var before = service.getChunksByFileId("file").stream().filter(chunk -> chunk.getId() == 11L).findFirst().orElseThrow();
        var empty = transaction.execute(status -> service.updateChunk(11L, new ChunkUpdateRequest()));
        assertEquals(before, empty);
        var patch = new ChunkUpdateRequest(); patch.setTitle("人工标题");
        var edited = transaction.execute(status -> service.updateChunk(11L, patch));
        assertEquals("人工标题", edited.getTitle());
        assertEquals(before.getContent(), edited.getContent());
        assertEquals(before.getVectorId(), edited.getVectorId());
        assertEquals(before.getHitCount(), edited.getHitCount());
        assertEquals("人工标题", db.jdbc().queryForObject("SELECT title FROM knowledge_chunk WHERE id=11", String.class));
    }

    @Test
    void blankContentRejectsEntirePatch() {
        var patch = new ChunkUpdateRequest(); patch.setTitle("不应写入"); patch.setContent(" \n ");
        assertThrows(IllegalArgumentException.class, () -> transaction.execute(status -> service.updateChunk(11L, patch)));
        assertEquals("原标题", db.jdbc().queryForObject("SELECT title FROM knowledge_chunk WHERE id=11", String.class));
    }

    @Test
    void outerTransactionFailureRollsBackTheEditedFields() {
        var patch = new ChunkUpdateRequest(); patch.setContent("修改正文");
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            assertEquals("修改正文", service.updateChunk(11L, patch).getContent());
            throw new IllegalStateException("later owner failure");
        }));
        assertEquals("匹配正文", db.jdbc().queryForObject("SELECT content FROM knowledge_chunk WHERE id=11", String.class));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 0, 1, 2})
    void toggleNormalizesInputWithoutRewritingVector(Integer enabled) {
        var updated = transaction.execute(status -> service.toggleChunk(11L, enabled));
        assertEquals(enabled != null && enabled == 0 ? 0 : 1, updated.getEnabled());
        assertEquals("vector-11", updated.getVectorId());
        assertEquals("匹配正文", updated.getContent());
    }

    private List<Long> ids(List<ChunkVO> chunks) { return chunks.stream().map(ChunkVO::getId).toList(); }
}
