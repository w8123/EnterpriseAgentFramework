package com.enterprise.ai.service.impl;

import com.enterprise.ai.pipeline.document.job.DocumentIndexVectorManifest;
import com.enterprise.ai.vector.VectorSearchRequest;
import com.enterprise.ai.vector.impl.MilvusVectorService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.collection.FlushParam;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Kills only JVMs started by this test; all SQL and vectors belong to its isolated fixtures. */
@EnabledIfSystemProperty(named = "reachai.mysql.publicationVerification", matches = "true")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MILVUS_HOST", matches = ".+")
class KnowledgeIndexProcessCrashIT {
    static Stream<String> stages() {
        List<String> all = List.of("before-upsert", "response-held", "acknowledged", "publication-open", "publication-committed", "cleanup-partial");
        String selected = System.getProperty("reachai.processCrash.stages");
        if (selected == null) return all.stream();
        List<String> requested = Arrays.stream(selected.split(",", -1)).map(String::trim).toList();
        if (!all.containsAll(requested) || new HashSet<>(requested).size() != requested.size()) {
            throw new IllegalArgumentException("reachai.processCrash.stages must contain unique known crash stages");
        }
        return requested.stream();
    }

    @ParameterizedTest
    @MethodSource("stages")
    void newJvmRecoversAfterForcedTerminationAtRealPersistenceBoundaries(String stage) throws Exception {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String collection = "reachai_it_crash_" + nonce;
        Path artifacts = Path.of("../output/tasks/architecture-audit-20260905/knowledge-process-crash", stage + "_" + nonce).toAbsolutePath().normalize();
        Files.createDirectories(artifacts);
        var client = new MilvusServiceClient(KnowledgeIndexProcessProbe.connection().build());
        var vectors = new MilvusVectorService(client);
        var processes = new ArrayList<Process>();
        try (var source = new KnowledgePublicationMysqlDatabase()) {
            try {
                vectors.ensureCollection(collection, 2);
                var jdbc = new JdbcTemplate(source);
                jdbc.update("INSERT INTO knowledge_base(id,name,code,vector_collection_name,dimension) VALUES (7,'进程恢复知识库','process-business-kb',?,2)", collection);
                assertEquals("进程恢复知识库", jdbc.queryForObject("SELECT name FROM knowledge_base WHERE id=7", String.class));
                jdbc.update("INSERT INTO knowledge_document_import_job(job_id,file_id,knowledge_base_id,knowledge_base_code,file_name,file_type,source_object_key,provider_type,status,stage,lease_owner,lease_until,vector_collection_name) VALUES ('job','file',7,'process-business-kb','process.pdf','pdf','source','DOCLING','INDEXING','INDEXING','lease','2099-01-01 00:00:00',?)", collection);
                Path signal = artifacts.resolve("barrier.txt");
                Process crashed = start(source.namespace(), collection, stage, signal, artifacts);
                processes.add(crashed);
                waitForBarrier(crashed, stage, signal);
                boolean published = stage.equals("publication-committed");
                boolean acknowledged = !stage.equals("before-upsert") && !stage.equals("response-held");
                assertEquals(acknowledged ? 1 : 0, jdbc.queryForObject("SELECT write_acknowledged FROM knowledge_document_index_execution WHERE lease_owner='lease'", Integer.class));
                assertEquals(stage.equals("cleanup-partial") ? 1 : 0, jdbc.queryForObject("SELECT cleanup_cursor FROM knowledge_document_index_execution WHERE lease_owner='lease'", Integer.class));
                int vectorsBeforeKill = stage.equals("before-upsert") ? 0 : stage.equals("cleanup-partial") ? 2 : 3;
                assertCount(client, vectors, collection, vectorsBeforeKill);
                assertTrue(crashed.isAlive(), "The child must still be blocked before the explicit forced termination");
                crashed.destroyForcibly();
                assertTrue(crashed.waitFor(15, TimeUnit.SECONDS), "Owned child JVM did not terminate");
                assertNotEquals(0, crashed.exitValue(), "Forced termination must not be reported as normal completion");
                assertFalse(crashed.isAlive());
                assertEquals(published ? "COMPLETED" : stage.equals("cleanup-partial") ? "FAILED" : "INDEXING",
                        jdbc.queryForObject("SELECT status FROM knowledge_document_import_job WHERE job_id='job'", String.class));
                assertEquals(published ? 1 : 0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
                assertEquals(published ? 3 : 0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class));
                // Advance only scratch lease deadlines; actual worker and cleanup processes were terminated above.
                jdbc.update("UPDATE knowledge_document_import_job SET lease_until='2000-01-01 00:00:00' WHERE status='INDEXING'");
                jdbc.update("UPDATE knowledge_document_index_execution SET cleanup_lease_until='2000-01-01 00:00:00' WHERE state='RECLAIMING'");
                Process recovered = start(source.namespace(), collection, "recover", artifacts.resolve("unused-signal.txt"), artifacts);
                processes.add(recovered);
                assertNotEquals(crashed.pid(), recovered.pid());
                assertTrue(recovered.waitFor(90, TimeUnit.SECONDS), "Recovery JVM did not finish; inspect its artifact log");
                assertEquals(0, recovered.exitValue(), "Recovery JVM failed; inspect its artifact log");
                assertFalse(recovered.isAlive());
                String finalState = published ? "PUBLISHED" : acknowledged ? "RECLAIMED" : "RECLAIMING";
                assertEquals(finalState, jdbc.queryForObject("SELECT state FROM knowledge_document_index_execution WHERE lease_owner='lease'", String.class));
                assertCount(client, vectors, collection, published ? 3 : 0);
                assertEquals(published ? 1 : 0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_file_info", Integer.class));
                assertEquals(published ? 3 : 0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class));
                String recoveredLog = Files.readString(artifacts.resolve("recover.log"));
                if (published) {
                    assertEquals("进程恢复测试.pdf", jdbc.queryForObject("SELECT file_name FROM knowledge_file_info WHERE file_id='file'", String.class));
                    assertEquals(List.of("第一段进程测试正文", "第二段进程测试正文", "第三段进程测试正文"),
                            jdbc.queryForList("SELECT content FROM knowledge_chunk WHERE file_id='file' ORDER BY chunk_index", String.class));
                }
                assertTrue(recoveredLog.contains("PROCESS_RECOVERY_FINISHED pid=" + recovered.pid()));
                if (stage.equals("cleanup-partial")) {
                    assertFalse(recoveredLog.contains("Deleted vector id=" + DocumentIndexVectorManifest.forExecution("file", "lease", 3).vectorId(0)),
                            "Recovery must resume after the committed first deletion");
                }
                var evidence = new LinkedHashMap<String, Object>();
                evidence.put("stage", stage); evidence.put("namespace", source.namespace()); evidence.put("collection", collection);
                evidence.put("knowledgeBaseCode", jdbc.queryForObject("SELECT code FROM knowledge_base WHERE id=7", String.class));
                evidence.put("killedPid", crashed.pid()); evidence.put("killedExitCode", crashed.exitValue()); evidence.put("recoveryPid", recovered.pid());
                evidence.put("writeAcknowledgedAtKill", acknowledged); evidence.put("vectorsBeforeKill", vectorsBeforeKill);
                evidence.put("vectorsAfterRecovery", published ? 3 : 0); evidence.put("finalState", finalState);
                evidence.put("scratchLeaseDeadlinesAdvanced", true); evidence.put("childrenStopped", true);
                evidence.put("knowledgeBaseUtf8Readback", true); evidence.put("publishedTextUtf8Readback", published);
                Files.writeString(artifacts.resolve("verification.json"), new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
                System.out.println("MYSQL_MILVUS_PROCESS_CRASH_VERIFIED stage=" + stage + " killedPid=" + crashed.pid() + " recoveryPid=" + recovered.pid() + " evidence=" + artifacts.resolve("verification.json"));
            } finally {
                for (Process process : processes) if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Owned test child remains alive");
                }
                vectors.dropCollection(collection);
            }
        } finally { client.close(); }
    }

    private static Process start(String namespace, String collection, String stage, Path signal, Path artifacts) throws Exception {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path javaExecutable = Files.exists(bin.resolve("java.exe")) ? bin.resolve("java.exe") : bin.resolve("java");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var args = List.of("-Dfile.encoding=UTF-8", "-Dreachai.mysql.publicationVerification=true", "-cp", classpath,
                KnowledgeIndexProcessProbe.class.getName(), namespace, collection, stage, signal.toString());
        Path argumentFile = artifacts.resolve(stage + ".args");
        Files.writeString(argumentFile, args.stream().map(KnowledgeIndexProcessCrashIT::quote).collect(java.util.stream.Collectors.joining("\n")));
        return new ProcessBuilder(javaExecutable.toString(), "@" + argumentFile)
                .directory(Path.of("").toAbsolutePath().toFile()).redirectErrorStream(true)
                .redirectOutput(artifacts.resolve(stage + ".log").toFile()).start();
    }

    private static String quote(String arg) { return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    private static void waitForBarrier(Process process, String stage, Path signal) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!Files.exists(signal) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(Files.exists(signal), "Child did not reach " + stage + "; inspect its artifact log");
        assertEquals(stage + "\n" + process.pid(), Files.readString(signal));
        assertTrue(process.isAlive(), "Child exited before forced termination");
    }

    private static void assertCount(MilvusServiceClient client, MilvusVectorService vectors, String collection, int count) throws InterruptedException {
        assertEquals(io.milvus.param.R.Status.Success.getCode(), client.flush(FlushParam.newBuilder().addCollectionName(collection).withSyncFlush(true).build()).getStatus());
        var query = VectorSearchRequest.builder().collectionName(collection).queryVector(List.of(1.0f, 0.0f)).topK(5).build();
        for (int i=0; i<20 && vectors.search(query).size()!=count; i++) Thread.sleep(250);
        assertEquals(count, vectors.search(query).size());
    }
}
