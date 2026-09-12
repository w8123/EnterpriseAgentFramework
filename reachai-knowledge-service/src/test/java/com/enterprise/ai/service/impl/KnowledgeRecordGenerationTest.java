package com.enterprise.ai.service.impl;

import com.enterprise.ai.domain.entity.*;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineException;
import com.enterprise.ai.pipeline.document.job.*;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.security.impl.PermissionServiceImpl;
import com.enterprise.ai.support.ArtifactLifecycleTestSupport;
import com.enterprise.ai.support.KnowledgeQueryTestDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real mapper insertion and mutation boundaries, including deliberate primary-key reuse. */
class KnowledgeRecordGenerationTest {
    private KnowledgeQueryTestDatabase database;
    private Context context;

    @BeforeEach void setup() throws Exception {
        database = new KnowledgeQueryTestDatabase(List.of("knowledge_base", "knowledge_file_info", "knowledge_chunk",
                "knowledge_user_file_permission", "knowledge_document_import_job", "knowledge_document_index_execution"));
        context = new Context(database.jdbc().getDataSource());
    }
    @AfterEach void close() { if (database != null) database.close(); }

    @Test void insertsGenerateNewIdentitiesAndOrdinaryUpdatesCannotReplaceThem() throws Exception { context.verifyInsertionAndUpdates(); }
    @Test void anOldRequestCannotFollowReusedFileAndGrantPrimaryKeys() { context.verifyRequestIdentity(); }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reembeddingRejectsARebuiltFileEvenWithIdenticalChunkMetadata(boolean registered) { context.verifyReembedding(registered); }
    @Test void unchangedFileIdentityAllowsReembeddingPublication() { context.verifySuccessfulPublication(); }
    @Test void executionAndJobTargetGenerationsAreImmutable() { context.verifyFrozenTargets(); }
    @ParameterizedTest @ValueSource(strings = {"knowledge_file_info", "knowledge_user_file_permission"})
    void unknownRecordGenerationCannotAuthorizeRetrieval(String table) {
        context.jdbc.update("UPDATE " + table + " SET record_generation=NULL");
        assertTrue(context.permissions.capture("actor").grants().isEmpty());
        if (table.equals("knowledge_file_info")) assertThrows(IllegalStateException.class, () -> context.file().requireRecordGeneration());
    }

    static final class Context {
        final JdbcTemplate jdbc;
        final SqlSessionTemplate session;
        final TransactionTemplate tx;
        final DocumentIndexExecutionStore executions;
        final PermissionServiceImpl permissions;

        Context(DataSource source) throws Exception {
            jdbc = new JdbcTemplate(source);
            session = ArtifactLifecycleTestSupport.session(source, KnowledgeBaseRepository.class, FileInfoRepository.class,
                    ChunkRepository.class, UserFilePermissionRepository.class, DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class);
            var manager = new DataSourceTransactionManager(source); tx = new TransactionTemplate(manager);
            executions = new DocumentIndexExecutionStore(session.getMapper(DocumentImportJobRepository.class),
                    session.getMapper(DocumentIndexExecutionRepository.class), session.getMapper(KnowledgeBaseRepository.class),
                    session.getMapper(FileInfoRepository.class), session.getMapper(ChunkRepository.class), manager);
            permissions = new PermissionServiceImpl(session.getMapper(UserFilePermissionRepository.class), manager);
            jdbc.update("INSERT INTO knowledge_base(id,code,name,status,vector_collection_name) VALUES (7,'kb','记录身份验证',1,'generation_physical')");
            insertFile(); insertGrant();
            jdbc.update("INSERT INTO knowledge_chunk(id,file_id,knowledge_base_id,chunk_index,content,vector_id,collection_name,enabled) VALUES (101,'file',7,0,'身份校验正文','old-vector','generation_physical',1)");
            assertEquals("记录身份验证", jdbc.queryForObject("SELECT name FROM knowledge_base", String.class));
        }
        FileInfo file() { return session.getMapper(FileInfoRepository.class).selectById(11L); }
        FileInfo insertFile() {
            var file = new FileInfo(); file.setId(11L); file.setFileId("file"); file.setKnowledgeBaseId(7L); file.setFileName("身份校验文件");
            file.setRecordGeneration("0".repeat(32));
            assertEquals(1, session.getMapper(FileInfoRepository.class).insert(file));
            return file;
        }
        UserFilePermission insertGrant() {
            var grant = new UserFilePermission(); grant.setId(1L); grant.setUserId("actor"); grant.setFileId("file"); grant.setPermissionType("read");
            grant.setRecordGeneration("0".repeat(32));
            assertEquals(1, session.getMapper(UserFilePermissionRepository.class).insert(grant));
            return grant;
        }
        void verifyInsertionAndUpdates() throws Exception {
            var files = session.getMapper(FileInfoRepository.class); var grants = session.getMapper(UserFilePermissionRepository.class);
            var file = file(); var grant = grants.selectById(1L);
            String fileGeneration = file.requireRecordGeneration(), grantGeneration = grant.getRecordGeneration();
            assertNotEquals("0".repeat(32), fileGeneration); assertTrue(grantGeneration.matches("[0-9a-f]{32}"));
            assertNotEquals("0".repeat(32), grantGeneration); assertNotEquals(fileGeneration, grantGeneration);
            file.setRecordGeneration("1".repeat(32)); file.setFileName("已修改文件名");
            grant.setRecordGeneration("1".repeat(32)); grant.setPermissionType("write");
            files.updateById(file); grants.updateById(grant);
            assertEquals(fileGeneration, file().getRecordGeneration()); assertEquals("已修改文件名", file().getFileName());
            assertEquals(grantGeneration, grants.selectById(1L).getRecordGeneration()); assertEquals("write", grants.selectById(1L).getPermissionType());
            files.deleteById(11L); grants.deleteById(1L);
            file.setRecordGeneration(fileGeneration); grant.setRecordGeneration(grantGeneration);
            files.insert(file); grants.insert(grant);
            assertNotEquals(fileGeneration, file().getRecordGeneration()); assertNotEquals(grantGeneration, grants.selectById(1L).getRecordGeneration());
            var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            assertFalse(json.writeValueAsString(file()).contains("recordGeneration"));
            assertFalse(json.writeValueAsString(grants.selectById(1L)).contains("recordGeneration"));
        }
        void verifyRequestIdentity() {
            var original = permissions.capture("actor");
            assertEquals(1, permissions.resolveAuthorizedChunks(original, List.of(101L)).size());
            session.getMapper(FileInfoRepository.class).deleteById(11L); insertFile();
            assertTrue(permissions.resolveAuthorizedChunks(original, List.of(101L)).isEmpty());
            var current = permissions.capture("actor"); assertEquals(1, permissions.resolveAuthorizedChunks(current, List.of(101L)).size());
            session.getMapper(UserFilePermissionRepository.class).deleteById(1L); insertGrant();
            assertTrue(permissions.resolveAuthorizedChunks(current, List.of(101L)).isEmpty());
            assertEquals(1, permissions.resolveAuthorizedChunks(permissions.capture("actor"), List.of(101L)).size());
        }
        PipelineContext reembedding() {
            var c = new PipelineContext(); c.setKnowledgeBaseId(7L); c.setKnowledgeBaseCode("kb"); c.setVectorCollectionName("generation_physical");
            c.setFileId("file"); c.setIndexOperation("REEMBED"); c.setIndexExecutionId(UUID.randomUUID().toString());
            c.setIndexTarget(DocumentIndexTargetSnapshot.from(file(), session.getMapper(ChunkRepository.class).selectById(101L)));
            c.setChunks(List.of("身份校验正文"));
            c.setVectorIds(DocumentIndexVectorManifest.forExecution("file", c.getIndexExecutionId(), 1).batch(0, 1));
            return c;
        }
        void register(PipelineContext c) {
            assertTrue(executions.register(c, DocumentIndexVectorManifest.forExecution("file", c.getIndexExecutionId(), 1)));
            executions.acknowledge(c.getIndexExecutionId());
        }
        void verifyReembedding(boolean registered) {
            var c = reembedding(); if (registered) register(c);
            session.getMapper(FileInfoRepository.class).deleteById(11L); var replacement = insertFile();
            if (registered) assertThrows(PipelineException.class, () -> tx.executeWithoutResult(status -> executions.publishReplacement(c)));
            else assertThrows(PipelineException.class, () -> register(c));
            assertEquals(replacement.getRecordGeneration(), file().getRecordGeneration());
            assertEquals("old-vector", jdbc.queryForObject("SELECT vector_id FROM knowledge_chunk", String.class));
            assertEquals("身份校验正文", jdbc.queryForObject("SELECT content FROM knowledge_chunk", String.class));
        }
        void verifySuccessfulPublication() {
            var c = reembedding(); register(c); tx.executeWithoutResult(status -> executions.publishReplacement(c));
            assertEquals(c.getVectorIds().get(0), jdbc.queryForObject("SELECT vector_id FROM knowledge_chunk", String.class));
            assertEquals("PUBLISHED", session.getMapper(DocumentIndexExecutionRepository.class).selectById(c.getIndexExecutionId()).getState());
            assertEquals(c.getIndexTarget().fileGeneration(), file().getRecordGeneration());
        }
        void verifyFrozenTargets() {
            var c = reembedding(); register(c);
            var repository = session.getMapper(DocumentIndexExecutionRepository.class); var e = repository.selectById(c.getIndexExecutionId());
            e.setTargetFileGeneration("1".repeat(32)); e.setLastCleanupError("fixture"); repository.updateById(e);
            assertEquals(c.getIndexTarget().fileGeneration(), repository.selectById(e.getLeaseOwner()).getTargetFileGeneration());
            assertEquals("fixture", repository.selectById(e.getLeaseOwner()).getLastCleanupError());
            jdbc.update("INSERT INTO knowledge_document_import_job(job_id,file_id,replace_file_id,replace_file_row_id,replace_file_generation,knowledge_base_id,knowledge_base_code,file_name,file_type,source_object_key,provider_type,status,stage) VALUES ('job','new-file','file',11,?,7,'kb','重解析文件','txt','fixture/source','JAVA_FAST','QUEUED','QUEUED')", file().getRecordGeneration());
            var jobs = session.getMapper(DocumentImportJobRepository.class); var job = jobs.selectOne(null);
            job.setReplaceFileGeneration("1".repeat(32)); job.setErrorMessage("已修改说明"); jobs.updateById(job);
            assertEquals(file().getRecordGeneration(), jobs.selectById(job.getId()).getReplaceFileGeneration());
            assertEquals("已修改说明", jobs.selectById(job.getId()).getErrorMessage());
        }
    }
}
