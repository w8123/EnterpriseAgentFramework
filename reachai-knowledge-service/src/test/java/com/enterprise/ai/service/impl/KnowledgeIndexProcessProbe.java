package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.document.job.*;
import com.enterprise.ai.pipeline.step.MetadataPersistStep;
import com.enterprise.ai.pipeline.step.VectorStoreStep;
import com.enterprise.ai.repository.*;
import com.enterprise.ai.vector.impl.MilvusVectorService;
import io.grpc.*;
import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MilvusServiceGrpc;
import io.milvus.grpc.MutationResult;
import io.milvus.param.ConnectParam;
import io.milvus.param.RetryParam;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Child JVM entry used only by the explicitly enabled process-crash acceptance test. */
public final class KnowledgeIndexProcessProbe {
    private KnowledgeIndexProcessProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected fixture, collection, stage and signal path");
        String collection = args[1]; String stage = args[2];
        if (!collection.matches("reachai_it_crash_[0-9a-f]{32}")
                || !Set.of("before-upsert", "response-held", "acknowledged", "publication-open", "publication-committed", "cleanup-partial", "recover").contains(stage)) {
            throw new IllegalArgumentException("Invalid process fixture identity");
        }
        Path signal = Path.of(args[3]).toAbsolutePath().normalize();
        Path artifactRoot = Path.of("../output/tasks/architecture-audit-20260905/knowledge-process-crash").toAbsolutePath().normalize();
        if (!signal.startsWith(artifactRoot)) throw new IllegalArgumentException("Signal outside process test artifacts");
        try (var source = KnowledgePublicationMysqlDatabase.attach(args[0])) {
            var jdbc = new JdbcTemplate(source);
            if (!collection.equals(jdbc.queryForObject("SELECT b.vector_collection_name FROM knowledge_document_import_job j JOIN knowledge_base b ON j.knowledge_base_id=b.id AND j.knowledge_base_code=b.code WHERE j.job_id='job' AND j.file_id='file' AND b.code='process-business-kb'", String.class))) {
                throw new IllegalStateException("Parent job does not match the process fixture");
            }
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            for (Class<?> type : List.of(DocumentImportJobRepository.class, DocumentIndexExecutionRepository.class,
                    KnowledgeBaseRepository.class, FileInfoRepository.class, ChunkRepository.class)) config.addMapper(type);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
            var session = new SqlSessionTemplate(factory.getObject());
            var jobs = session.getMapper(DocumentImportJobRepository.class);
            var executions = session.getMapper(DocumentIndexExecutionRepository.class);
            var manager = new DataSourceTransactionManager(source);
            var store = KnowledgeIndexTestSupport.executions(session, manager);
            MilvusServiceClient base = new MilvusServiceClient(connection().build());
            MilvusServiceClient client = stage.equals("response-held")
                    ? new GatedMilvusClient(base, responseGate(() -> barrier(stage, signal))) : base;
            try {
                var vectors = new MilvusVectorService(client) {
                    @Override public void upsert(String name, List<String> ids, List<List<Float>> data, List<String> files, List<String> content) {
                        if (stage.equals("before-upsert")) barrier(stage, signal);
                        super.upsert(name, ids, data, files, content);
                    }
                    @Override public void deleteById(String name, String id) {
                        if (stage.equals("cleanup-partial") && id.endsWith("_chunk_1")) barrier(stage, signal);
                        super.deleteById(name, id);
                    }
                };
                if (stage.equals("recover")) {
                    jobs.failExpiredIndexingLeases();
                    new DocumentIndexReclaimer(executions, store, vectors, new DocumentImportJobProperties()).reclaimPending();
                    System.out.println("PROCESS_RECOVERY_FINISHED pid=" + ProcessHandle.current().pid());
                    return;
                }
                var context = context(collection);
                var vectorStep = new VectorStoreStep(vectors, store);
                vectorStep.process(context);
                if (stage.equals("acknowledged")) barrier(stage, signal);
                if (stage.equals("cleanup-partial")) {
                    jdbc.update("UPDATE knowledge_document_import_job SET lease_until='2000-01-01 00:00:00' WHERE job_id='job'");
                    jobs.failExpiredIndexingLeases();
                    new DocumentIndexReclaimer(executions, store, vectors, new DocumentImportJobProperties()).reclaimPending();
                } else {
                    var metadata = new MetadataPersistStep(session.getMapper(KnowledgeBaseRepository.class),
                            session.getMapper(FileInfoRepository.class), session.getMapper(ChunkRepository.class),
                            new com.fasterxml.jackson.databind.ObjectMapper(), new DocumentImportPublicationGuard(jobs, store, org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeFileDeletionService.class)), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeTagService.class), org.mockito.Mockito.mock(com.enterprise.ai.service.impl.KnowledgeQuestionService.class));
                    new TransactionTemplate(manager).executeWithoutResult(status -> {
                        metadata.process(context);
                        if (stage.equals("publication-open")) barrier(stage, signal);
                    });
                    if (stage.equals("publication-committed")) barrier(stage, signal);
                }
                throw new IllegalStateException("Process did not reach its expected barrier");
            } finally { client.close(); }
        }
    }

    static ConnectParam.Builder connection() {
        return ConnectParam.newBuilder().withHost(System.getenv("MILVUS_HOST"))
                .withPort(Integer.parseInt(System.getenv("MILVUS_PORT")))
                .withAuthorization(System.getenv("MILVUS_USERNAME"), System.getenv("MILVUS_PASSWORD"));
    }

    static PipelineContext context(String collection) {
        var c = new PipelineContext(); c.setImportJobId("job"); c.setImportLeaseOwner("lease");
        c.setFileId("file"); c.setFileName("进程恢复测试.pdf"); c.setKnowledgeBaseCode("process-business-kb"); c.setKnowledgeBaseId(7L); c.setVectorCollectionName(collection);
        c.setChunks(List.of("第一段进程测试正文", "第二段进程测试正文", "第三段进程测试正文"));
        c.setVectors(Collections.nCopies(3, List.of(1.0f, 0.0f)));
        return c;
    }

    private static void barrier(String stage, Path signal) {
        try {
            Path pending = signal.resolveSibling(signal.getFileName() + ".pending");
            Files.writeString(pending, stage + "\n" + ProcessHandle.current().pid(), StandardOpenOption.CREATE_NEW);
            Files.move(pending, signal, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("PROCESS_CRASH_BARRIER stage=" + stage + " pid=" + ProcessHandle.current().pid());
            new CountDownLatch(1).await();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Barrier interrupted", e); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot publish process barrier", e); }
    }

    private static ClientInterceptor responseGate(Runnable barrier) {
        return new ClientInterceptor() {
            @Override public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
                var call = next.newCall(method, options);
                if (!method.getFullMethodName().equals(MilvusServiceGrpc.getUpsertMethod().getFullMethodName())) return call;
                return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
                    @Override public void start(Listener<RespT> listener, Metadata headers) {
                        super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(listener) {
                            @Override public void onMessage(RespT message) {
                                if (message instanceof MutationResult result
                                        && result.getStatus().getErrorCode() == io.milvus.grpc.ErrorCode.Success) barrier.run();
                                super.onMessage(message);
                            }
                        }, headers);
                    }
                };
            }
        };
    }

    private static final class GatedMilvusClient extends MilvusServiceClient {
        private final ClientInterceptor gate;
        private GatedMilvusClient(MilvusServiceClient client, ClientInterceptor gate) { super(client); this.gate = gate; }
        @Override protected MilvusServiceGrpc.MilvusServiceBlockingStub blockingStub() { return super.blockingStub().withInterceptors(gate); }
        @Override public MilvusClient withRetry(RetryParam retry) {
            // Production creates a retry-configured copy; retain the response gate on that copy too.
            return new GatedMilvusClient((MilvusServiceClient) super.withRetry(retry), gate);
        }
    }
}
