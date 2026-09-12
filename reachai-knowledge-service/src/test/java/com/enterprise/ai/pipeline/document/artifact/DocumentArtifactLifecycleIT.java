package com.enterprise.ai.pipeline.document.artifact;

import com.enterprise.ai.domain.dto.DocumentImportAccessContext;
import com.enterprise.ai.pipeline.document.*;
import com.enterprise.ai.pipeline.document.job.DocumentImportJobProperties;
import com.enterprise.ai.pipeline.document.job.DocumentImportJobWorker;
import com.enterprise.ai.pipeline.document.job.DocumentIndexExecutionStore;
import com.enterprise.ai.pipeline.step.ChunkStep;
import com.enterprise.ai.pipeline.step.TextCleanStep;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.service.PipelineImportService;
import com.enterprise.ai.service.impl.DocumentImportJobServiceImpl;
import com.enterprise.ai.service.impl.KnowledgeFileDeletionService;
import com.enterprise.ai.service.impl.KnowledgePublicationMysqlDatabase;
import com.enterprise.ai.support.ArtifactLifecycleTestSupport;
import com.enterprise.ai.vector.VectorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL, real local object bytes and real worker metadata; parser/model calls remain substituted. */
@EnabledIfSystemProperty(named = "reachai.mysql.publicationVerification", matches = "true")
public class DocumentArtifactLifecycleIT {
    private static final String KEY = "knowledge-document-import/dij_artifact_fixture/source/original";
    private static final DocumentImportAccessContext ACCESS =
            new DocumentImportAccessContext("tenant", "actor", "workspace", null, "WORKSPACE");
    @TempDir Path directory;

    @Test
    void sourceAndParsedArtifactsSurviveSharedReferencesAndRolledBackDeletion() throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withArtifactLifecycle(5)) {
            var context = new Context(source, directory.resolve("shared"));
            context.seed();
            String job = context.submit();
            context.worker.process(job);
            assertEquals("PARSED", context.jdbc.queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
            var keys = context.jdbc.queryForMap("SELECT source_object_key,parse_artifact_object_key FROM knowledge_document_import_job");
            String original = keys.get("source_object_key").toString(), parsed = keys.get("parse_artifact_object_key").toString();
            assertEquals("真实原件内容", context.read(original));
            assertTrue(context.read(parsed).contains("真实解析正文"));
            assertEquals(2, context.count("state='RETAINED'"));
            new TransactionTemplate(context.manager).executeWithoutResult(status -> {
                context.file("first", original, parsed);
                context.file("second", original, parsed);
                context.jdbc.update("UPDATE knowledge_document_import_job SET status='COMPLETED'");
            });
            new TransactionTemplate(context.manager).executeWithoutResult(status -> {
                context.deletion().deleteByFileId("kb", "first");
                status.setRollbackOnly();
            });
            assertEquals(2, context.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
            assertEquals(2, context.count("state='RETAINED'"));
            context.deletion().deleteByFileId("kb", "first");
            context.reclaimer().reclaimPending();
            assertEquals("真实原件内容", context.read(original));
            assertEquals(2, context.count("state='RECLAIMING'"));
            context.deletion().deleteByFileId("kb", "second");
            context.due();
            // Reconstruct both the backend and lifecycle service from committed state.
            new Context(source, context.root).reclaimer().reclaimPending();
            assertFalse(Files.exists(context.root.resolve(original)));
            assertFalse(Files.exists(context.root.resolve(parsed)));
            assertEquals(2, context.count("state='RECLAIMED'"));
            System.out.println("MYSQL_LOCAL_ARTIFACT_SHARED_RETIREMENT_VERIFIED");
        }
    }

    @Test
    void expiredParserCannotPublishItsArtifactAndCancellationRetiresTheSourceAtomically() throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withArtifactLifecycle(5)) {
            var context = new Context(source, directory.resolve("stale-parse"));
            context.seed();
            String job = context.submit();
            doAnswer(call -> {
                context.jdbc.update("UPDATE knowledge_document_import_job SET lease_until=TIMESTAMPADD(SECOND,-1,NOW()) WHERE job_id=?", job);
                return parsed();
            }).when(context.router).parse(any());
            context.worker.process(job);
            assertNull(context.jdbc.queryForObject("SELECT parse_artifact_object_key FROM knowledge_document_import_job", String.class));
            assertEquals("PARSING", context.jdbc.queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
            assertEquals(1, context.count("state='RECLAIMING'"));
            context.reclaimer().reclaimPending();
            assertEquals(1, context.count("state='RECLAIMED'"));
            new TransactionTemplate(context.manager).executeWithoutResult(status -> {
                context.service.cancel(job, ACCESS);
                status.setRollbackOnly();
            });
            assertEquals("PARSING", context.jdbc.queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
            assertEquals(1, context.count("state='RETAINED'"));
            new TransactionTemplate(context.manager).executeWithoutResult(status -> context.service.cancel(job, ACCESS));
            context.reclaimer().reclaimPending();
            assertEquals("CANCELLED", context.jdbc.queryForObject("SELECT status FROM knowledge_document_import_job", String.class));
            assertEquals(2, context.count("state='RECLAIMED'"));
            System.out.println("MYSQL_LOCAL_ARTIFACT_PARSE_LEASE_CANCEL_VERIFIED");
        }
    }

    @Test
    void publicationChecksTheDatabaseClockAfterWaitingForTheJournalLock() throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withArtifactLifecycle(5)) {
            var context = new Context(source, directory.resolve("deadline"));
            context.write(KEY);
            String id = DocumentArtifactIdentity.artifact(context.backend.storageId(), KEY);
            context.jdbc.update("UPDATE knowledge_document_artifact_lifecycle SET publication_deadline=TIMESTAMPADD(SECOND,2,NOW()) WHERE artifact_id=?", id);
            var observer = new WaitingArtifactLock();
            context.session.getConfiguration().addInterceptor(observer);
            var executor = Executors.newSingleThreadExecutor();
            var pending = new AtomicReference<Future<?>>();
            try {
                new TransactionTemplate(context.manager).executeWithoutResult(status -> {
                    context.jdbc.queryForList("SELECT artifact_id FROM knowledge_document_artifact_lifecycle WHERE artifact_id=? FOR UPDATE", id);
                    pending.set(executor.submit(() -> new TransactionTemplate(context.manager)
                            .executeWithoutResult(tx -> context.store.retain(KEY))));
                    try {
                        assertTrue(observer.started.await(2, TimeUnit.SECONDS));
                        assertFalse(pending.get().isDone());
                        Thread.sleep(2300);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt(); throw new AssertionError(failure);
                    }
                });
                var failure = assertThrows(ExecutionException.class, () -> pending.get().get(10, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertEquals(1, context.count("state='WRITING'"));
                context.reclaimer().reclaimPending();
                assertEquals(1, context.count("state='RECLAIMED'"));
                assertFalse(Files.exists(context.root.resolve(KEY)));
                System.out.println("MYSQL_LOCAL_ARTIFACT_POST_LOCK_DEADLINE_VERIFIED");
            } finally {
                executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void anotherJvmDiscoversAnUnacknowledgedPartialWriteAndKeepsSweepingLateBytes() throws Exception {
        try (var source = KnowledgePublicationMysqlDatabase.withArtifactLifecycle(5)) {
            var context = new Context(source, directory.resolve("crash"));
            Path log = directory.resolve("mysql-artifact-writer.log");
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dreachai.mysql.publicationVerification=true", "-cp", classpath,
                    DocumentArtifactLifecycleIT.class.getName(), source.namespace(), context.root.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Owned crash probe must terminate");
                assertEquals(25, process.exitValue(), Files.readString(log));
                assertTrue(Files.readString(log).contains("MYSQL_ARTIFACT_WRITER_HALTING_AFTER_JOURNAL_AND_FIRST_BLOCK"));
                assertEquals(1, context.count("state='WRITING' AND write_acknowledged=0"));
                try (var files = Files.list(context.root.resolve(".reachai-upload"))) {
                    assertEquals(8192, Files.size(files.findFirst().orElseThrow()));
                }
                context.jdbc.update("UPDATE knowledge_document_artifact_lifecycle SET publication_deadline=TIMESTAMPADD(SECOND,-1,NOW())");
                var recovered = new Context(source, context.root);
                recovered.reclaimer().reclaimPending();
                try (var files = Files.list(context.root.resolve(".reachai-upload"))) { assertEquals(0, files.count()); }
                assertEquals(1, context.count("state='RECLAIMING' AND write_acknowledged=0"));
                byte[] bytes = "受控迟到写入".getBytes(StandardCharsets.UTF_8);
                recovered.backend.put(KEY, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
                context.due();
                recovered.reclaimer().reclaimPending();
                assertFalse(Files.exists(context.root.resolve(KEY)));
                assertEquals(1, context.count("state='RECLAIMING' AND write_acknowledged=0"));
                System.out.println("MYSQL_LOCAL_ARTIFACT_JVM_CRASH_VERIFIED pid=" + process.pid()
                        + " exit=25 stagingBytes=8192 removed=true unknown_write_resweep=true");
            } finally {
                if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
            }
        }
    }

    private static DocumentParseResult parsed() {
        return DocumentParseResult.builder().providerType(DocumentProviderType.DOCLING)
                .providerVersion("fixture").format(DocumentFormat.TXT).normalizedText("真实解析正文").build();
    }

    private static final class Context {
        final Path root;
        final JdbcTemplate jdbc;
        final SqlSessionTemplate session;
        final DataSourceTransactionManager manager;
        final LocalDocumentArtifactStore backend;
        final DocumentArtifactLifecycleStore journal;
        final DocumentArtifactStore store;
        final DocumentParseRouter router;
        final DocumentImportJobWorker worker;
        final DocumentImportJobServiceImpl service;

        Context(KnowledgePublicationMysqlDatabase source, Path root) throws Exception {
            this.root = root;
            jdbc = new JdbcTemplate(source);
            session = ArtifactLifecycleTestSupport.session(source, KnowledgeBaseRepository.class, FileInfoRepository.class,
                    ChunkRepository.class, DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class,
                    UserFilePermissionRepository.class, KnowledgeQuestionRepository.class, DocumentArtifactLifecycleRepository.class);
            manager = new DataSourceTransactionManager(source);
            var properties = new DocumentArtifactProperties(); properties.setLocalRoot(root.toString());
            backend = new LocalDocumentArtifactStore(properties); backend.initialize();
            journal = new DocumentArtifactLifecycleStore(session.getMapper(DocumentArtifactLifecycleRepository.class),
                    session.getMapper(DocumentImportJobRepository.class), session.getMapper(FileInfoRepository.class), manager);
            store = new ManagedDocumentArtifactStore(backend, journal, manager);
            router = mock(DocumentParseRouter.class);
            when(router.detect(any())).thenReturn(DocumentFormat.TXT);
            when(router.parse(any())).thenAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                DocumentParseRequest request = call.getArgument(0);
                try (var content = request.openStream()) {
                    assertEquals("真实原件内容", new String(content.readAllBytes(), StandardCharsets.UTF_8));
                }
                return parsed();
            });
            var jobProperties = new DocumentImportJobProperties(); jobProperties.setEnabled(false);
            worker = new DocumentImportJobWorker(session.getMapper(DocumentImportJobRepository.class), store, router,
                    mock(PipelineImportService.class), mock(VectorService.class), new ObjectMapper(), jobProperties, manager);
            service = new DocumentImportJobServiceImpl(session.getMapper(DocumentImportJobRepository.class),
                    session.getMapper(KnowledgeBaseRepository.class), session.getMapper(FileInfoRepository.class), store, router,
                    new ObjectMapper(), jobProperties, worker, mock(TextCleanStep.class), mock(ChunkStep.class), Runnable::run, manager);
        }

        void seed() {
            jdbc.update("INSERT INTO knowledge_base(id,code,name,workspace_id,scope,vector_collection_name) VALUES (7,'kb','工件生命周期验证','workspace','WORKSPACE','reachai_kb_artifact_fixture')");
            assertEquals("工件生命周期验证", jdbc.queryForObject("SELECT name FROM knowledge_base", String.class));
        }

        String submit() {
            var upload = new MockMultipartFile("file", "中文原件.txt", "text/plain", "真实原件内容".getBytes(StandardCharsets.UTF_8));
            return service.submit(upload, "kb", "fixed_length", 500, 50, Map.of(), false, ACCESS).getJobId();
        }

        void file(String file, String original, String parsed) {
            jdbc.update("INSERT INTO knowledge_file_info(file_id,knowledge_base_id,file_name,source_object_key,parse_artifact_object_key) VALUES (?,7,'已发布文件',?,?)", file, original, parsed);
            assertEquals("已发布文件", jdbc.queryForObject("SELECT file_name FROM knowledge_file_info WHERE file_id=?", String.class, file));
        }

        KnowledgeFileDeletionService deletion() {
            return new KnowledgeFileDeletionService(session.getMapper(KnowledgeBaseRepository.class),
                    session.getMapper(FileInfoRepository.class), session.getMapper(ChunkRepository.class),
                    session.getMapper(DocumentImportJobRepository.class), session.getMapper(DocumentIndexExecutionRepository.class),
                    mock(DocumentIndexExecutionStore.class), store, session.getMapper(UserFilePermissionRepository.class), new com.enterprise.ai.service.impl.KnowledgeQuestionService(session.getMapper(KnowledgeBaseRepository.class), session.getMapper(ChunkRepository.class), session.getMapper(KnowledgeQuestionRepository.class), manager), manager, org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class));
        }

        DocumentArtifactReclaimer reclaimer() {
            return new DocumentArtifactReclaimer(backend, session.getMapper(DocumentArtifactLifecycleRepository.class), journal);
        }

        int count(String condition) { return jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_artifact_lifecycle WHERE " + condition, Integer.class); }
        void due() { jdbc.update("UPDATE knowledge_document_artifact_lifecycle SET next_cleanup_at=TIMESTAMPADD(SECOND,-1,NOW())"); }
        void write(String key) { byte[] bytes = "原件".getBytes(StandardCharsets.UTF_8); store.put(key, new ByteArrayInputStream(bytes), bytes.length, "text/plain"); }
        String read(String key) throws Exception { try (var input = store.open(key)) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); } }
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}))
    static class WaitingArtifactLock implements Interceptor {
        final CountDownLatch started = new CountDownLatch(1);
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var statement = (StatementHandler) invocation.getTarget();
            if (statement.getBoundSql().getSql().contains("SELECT * FROM knowledge_document_artifact_lifecycle WHERE artifact_id")) started.countDown();
            return invocation.proceed();
        }
    }

    /** Child attaches only to the parent's exact scratch tables and bypasses Java shutdown hooks. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Parent namespace and temporary artifact root required");
        try (var source = KnowledgePublicationMysqlDatabase.attachArtifactLifecycle(args[0])) {
            var context = new Context(source, Path.of(args[1]));
            context.store.put(KEY, new InputStream() {
                private int blocks;
                @Override public int read() { throw new UnsupportedOperationException("Block reader only"); }
                @Override public int read(byte[] bytes, int offset, int length) {
                    if (blocks++ == 0) {
                        int count = Math.min(8192, length); Arrays.fill(bytes, offset, offset + count, (byte) 'x'); return count;
                    }
                    System.out.println("MYSQL_ARTIFACT_WRITER_HALTING_AFTER_JOURNAL_AND_FIRST_BLOCK");
                    System.out.flush(); Runtime.getRuntime().halt(25); throw new AssertionError("halt returned");
                }
            }, 16384, "application/octet-stream");
        }
    }
}
