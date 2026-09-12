package com.enterprise.ai.capability.registry;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.registry.ProjectInstanceEntity;
import com.enterprise.ai.agent.registry.ProjectInstanceMapper;
import com.enterprise.ai.agent.registry.RegistryContracts.InstanceHeartbeatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Two real transactions interleave at the next instance write; mapper results are never mocked. */
class RegistryInstanceConcurrencyTest {
    AnnotationConfigApplicationContext context;
    RegistryInstanceLifecycleService lifecycle;
    ProjectInstanceMapper instances;
    TransactionTemplate tx;
    JdbcTemplate jdbc;
    ScanProjectEntity project;
    WriteGate gate;
    RegistryMysqlTestDatabase mysql;

    @Configuration
    @EnableTransactionManagement
    static class Transactions { }

    @BeforeEach void database() throws Exception {
        DataSource datasource;
        if (Boolean.getBoolean("reachai.mysql.instanceVerification")) {
            mysql = RegistryMysqlTestDatabase.instances();
            datasource = mysql;
            jdbc = new JdbcTemplate(datasource);
        } else {
            datasource = new DriverManagerDataSource("jdbc:h2:mem:instance_race_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
            jdbc = new JdbcTemplate(datasource);
            var match = Pattern.compile("(?is)CREATE TABLE IF NOT EXISTS `capability_project_instance`\\s*\\(.*?;")
                    .matcher(Files.readString(Path.of("../sql/initV2.sql")));
            assertTrue(match.find());
            jdbc.execute(match.group().replaceAll("(?is)\\) ENGINE=.*?;", ");")
                    .replaceAll("(?i)\\bJSON\\b", "LONGTEXT"));
        }
        gate = new WriteGate();
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ProjectInstanceMapper.class);
        configuration.addInterceptor(gate);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(datasource); factory.setConfiguration(configuration);
        instances = new SqlSessionTemplate(factory.getObject()).getMapper(ProjectInstanceMapper.class);
        var manager = new DataSourceTransactionManager(datasource);
        tx = new TransactionTemplate(manager);
        if (mysql != null) tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> manager);
        context.registerBean(RegistryInstanceLifecycleService.class,
                () -> new RegistryInstanceLifecycleService(instances, new ObjectMapper()));
        context.refresh();
        lifecycle = context.getBean(RegistryInstanceLifecycleService.class);
        project = new ScanProjectEntity(); project.setId(7L); project.setProjectCode("orders");
        project.setBaseUrl("http://orders.local");
    }

    @AfterEach void close() {
        try { if (context != null) context.close(); }
        finally { if (mysql != null) mysql.close(); }
    }

    @Test void pausedHeartbeatCannotOverwriteCommittedDisable() throws Exception {
        heartbeat("initial");
        race(() -> lifecycle.heartbeat(project, request("new")), () -> status("DISABLED"));
        assertEquals("DISABLED", current().getStatus());
        assertEquals("new", current().getAppVersion());
    }

    @Test void pausedShutdownCannotOverwriteCommittedDisable() throws Exception {
        heartbeat("initial");
        race(() -> lifecycle.offline(project, "worker"), () -> status("DISABLED"));
        assertEquals("DISABLED", current().getStatus());
    }

    @Test void pausedAdministrativeChangeCannotOverwriteNewHeartbeatDetails() throws Exception {
        heartbeat("initial");
        var observedAt = new AtomicReference<LocalDateTime>();
        race(() -> lifecycle.updateInstanceStatus(project, "worker", "DISABLED"), () -> {
            heartbeat("new"); observedAt.set(current().getUpdatedAt());
        });
        assertEquals("DISABLED", current().getStatus());
        assertEquals("new", current().getAppVersion());
        assertTrue(current().getMetadataJson().contains("新观察-new"));
        assertFalse(current().getUpdatedAt().isBefore(observedAt.get()));
    }

    @Test void cleanupCannotDeleteAnInstanceRevivedAfterCandidateSelection() throws Exception {
        heartbeat("initial"); status("OFFLINE");
        jdbc.update("UPDATE capability_project_instance SET last_heartbeat_at = ?", LocalDateTime.now().minusHours(2));
        var removed = new AtomicInteger(-1);
        race(() -> removed.set(lifecycle.purgeOfflineInstances(project, 30)), () -> heartbeat("revived"));
        assertEquals(0, removed.get());
        assertEquals("ONLINE", current().getStatus());
        assertEquals("revived", current().getAppVersion());
    }

    @Test void concurrentFirstHeartbeatsConvergeOnOneInstance() throws Exception {
        var secondObservation = new AtomicReference<LocalDateTime>();
        race(() -> lifecycle.heartbeat(project, request("first")), () -> {
            heartbeat("second"); secondObservation.set(current().getLastHeartbeatAt());
        });
        assertEquals(1, instances.selectCount(null));
        assertEquals("ONLINE", current().getStatus());
        assertEquals("first", current().getAppVersion());
        assertFalse(current().getLastHeartbeatAt().isBefore(secondObservation.get()));
    }

    @Test void heartbeatReplacesNullableObservationButPreservesIdentityAndOtherProjects() {
        heartbeat("initial"); status("DISABLED");
        ProjectInstanceEntity before = current();
        jdbc.update("INSERT INTO capability_project_instance (project_id, project_code, instance_id, status) VALUES (8, 'other', 'worker', 'DISABLED')");
        var response = tx.execute(s -> lifecycle.heartbeat(project,
                new InstanceHeartbeatRequest("worker", null, null, null, null, null, null)));
        var after = response.instance();
        assertEquals(before.getId(), after.getId());
        assertEquals(before.getCreatedAt(), after.getCreatedAt());
        assertEquals("DISABLED", after.getStatus());
        assertEquals(project.getBaseUrl(), after.getBaseUrl());
        assertNull(after.getHost()); assertNull(after.getPort());
        assertNull(after.getAppVersion()); assertNull(after.getSdkVersion()); assertNull(after.getMetadataJson());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM capability_project_instance WHERE project_code = 'other' AND status = 'DISABLED'", Integer.class));
    }

    @Test void missingAdministrativeTargetIsRejectedAndUnchangedStatusIsAccepted() {
        assertThrows(IllegalArgumentException.class, () -> status("DISABLED"));
        heartbeat("initial"); status("DISABLED"); status("DISABLED");
        assertEquals("DISABLED", current().getStatus());
        assertEquals(1, instances.selectCount(null));
    }

    @Test void outerFailureRollsBackHeartbeatAndAdministrativeStateTogether() {
        heartbeat("initial");
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            lifecycle.heartbeat(project, request("new"));
            lifecycle.updateInstanceStatus(project, "worker", "DISABLED");
            throw new IllegalStateException("outer command failed");
        }));
        assertEquals("initial", current().getAppVersion());
        assertEquals("ONLINE", current().getStatus());
    }

    private void heartbeat(String version) { tx.executeWithoutResult(s -> lifecycle.heartbeat(project, request(version))); }
    private void status(String status) { tx.executeWithoutResult(s -> lifecycle.updateInstanceStatus(project, "worker", status)); }
    private InstanceHeartbeatRequest request(String version) {
        return new InstanceHeartbeatRequest("worker", null, "host", 8080, version, "sdk", Map.of("note", "新观察-" + version));
    }
    private ProjectInstanceEntity current() { return instances.selectOne(null); }

    private void race(Runnable pausedTransaction, Runnable committedTransaction) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var first = executor.submit(() -> {
            gate.target = Thread.currentThread();
            tx.executeWithoutResult(s -> pausedTransaction.run());
        });
        try {
            assertTrue(gate.reached.await(10, TimeUnit.SECONDS), "First transaction did not reach its instance write");
            try { committedTransaction.run(); } finally { gate.release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        } finally {
            gate.release.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static class WriteGate implements Interceptor {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Thread target;

        @Override public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            if (Thread.currentThread() == target && statement.getId().startsWith(ProjectInstanceMapper.class.getName() + ".")) {
                target = null;
                reached.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent transaction did not release the write");
            }
            return invocation.proceed();
        }
    }
}
