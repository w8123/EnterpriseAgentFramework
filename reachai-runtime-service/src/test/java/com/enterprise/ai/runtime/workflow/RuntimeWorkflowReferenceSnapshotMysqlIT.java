package com.enterprise.ai.runtime.workflow;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** 在第一次覆盖查询之后提交独立写事务，验证真实注解事务保持同一 MySQL 快照。 */
@EnabledIfSystemProperty(named = "reachai.mysql.workflowReferenceVerification", matches = "true")
class RuntimeWorkflowReferenceSnapshotMysqlIT extends RuntimeWorkflowReferenceIndexPersistenceTest {
    @Override protected boolean useMysql() { return true; }

    @ParameterizedTest
    @ValueSource(strings = {"REINDEX", "DELETE", "INVALIDATE"})
    void readerKeepsCoverageAndHitsFromOneSnapshot(String change) throws Exception {
        var workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("snapshot-workflow"); workflow.setKeySlug("snapshot-workflow");
        workflow.setName("并发快照"); workflow.setStatus("DRAFT"); workflow.setGraphSpecJson(graph);
        workflow.setUpdatedAt(LocalDateTime.of(2026, 9, 10, 12, 0));
        tx.executeWithoutResult(status -> { workflows.insert(workflow); index.indexDraft(workflow); });

        var boundary = new SnapshotBoundary();
        session.getConfiguration().addInterceptor(boundary);
        var factory = new ProxyFactory(index);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(jdbc.getDataSource()),
                new AnnotationTransactionAttributeSource()));
        var transactionalIndex = (RuntimeWorkflowReferenceIndex) factory.getProxy();
        var reader = Executors.newSingleThreadExecutor(task -> new Thread(task, "reference-snapshot-reader"));
        try {
            var oldRead = reader.submit(() -> transactionalIndex.inspect(List.of("orders_read", "orders_new")));
            assertTrue(boundary.snapshotEstablished.await(10, TimeUnit.SECONDS), "读取必须先建立实际数据库快照");
            tx.executeWithoutResult(status -> {
                if ("DELETE".equals(change)) {
                    index.delete(workflow.getId()); workflows.deleteById(workflow.getId());
                } else {
                    workflow.setGraphSpecJson(graph.replace("orders_read", "orders_new"));
                    workflow.setUpdatedAt(workflow.getUpdatedAt().plusSeconds(1));
                    workflows.updateById(workflow);
                    if ("REINDEX".equals(change)) index.indexDraft(workflow);
                }
            });
            boundary.writerCommitted.countDown();
            var first = oldRead.get(10, TimeUnit.SECONDS);
            assertTrue(first.warnings().isEmpty());
            assertEquals(List.of("orders_read"), first.hits().stream().map(RuntimeWorkflowReferenceIndex.Hit::referenceKey).toList());
            assertEquals("读取订单", first.hits().get(0).nodeId());

            var next = transactionalIndex.inspect(List.of("orders_read", "orders_new"));
            if ("REINDEX".equals(change)) {
                assertTrue(next.warnings().isEmpty());
                assertEquals(List.of("orders_new"), next.hits().stream().map(RuntimeWorkflowReferenceIndex.Hit::referenceKey).toList());
            } else if ("DELETE".equals(change)) {
                assertTrue(next.warnings().isEmpty()); assertTrue(next.hits().isEmpty());
            } else {
                assertTrue(next.warnings().contains("REFERENCE_INDEX_INCOMPLETE"));
                assertEquals(List.of("orders_read"), next.hits().stream().map(RuntimeWorkflowReferenceIndex.Hit::referenceKey).toList());
            }
            System.out.println("WORKFLOW_REFERENCE_SNAPSHOT_PASSED " + change + " committed_between_queries=true");
        } finally {
            boundary.writerCommitted.countDown();
            reader.shutdownNow();
            assertTrue(reader.awaitTermination(15, TimeUnit.SECONDS), "读取线程必须退出后才能清理克隆表");
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class SnapshotBoundary implements Interceptor {
        final CountDownLatch snapshotEstablished = new CountDownLatch(1);
        final CountDownLatch writerCommitted = new CountDownLatch(1);
        private final AtomicBoolean paused = new AtomicBoolean();

        @Override public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            if (((MappedStatement) invocation.getArgs()[0]).getId().endsWith(".missingDraftIds")
                    && Thread.currentThread().getName().equals("reference-snapshot-reader")
                    && paused.compareAndSet(false, true)) {
                snapshotEstablished.countDown();
                if (!writerCommitted.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("并发写事务未在期限内提交");
            }
            return result;
        }
    }
}
