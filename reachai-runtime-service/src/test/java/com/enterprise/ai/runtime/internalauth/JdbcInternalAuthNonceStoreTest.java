package com.enterprise.ai.runtime.internalauth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.sql.Timestamp;
import java.sql.SQLException;
import org.springframework.dao.CannotAcquireLockException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * JDBC anti-replay proofs against shared in-memory H2 (unique PK).
 * Full MySQL/Testcontainers multi-node proof remains LIVE/JDBC_MYSQL_PENDING when Docker MySQL is unavailable.
 */
class JdbcInternalAuthNonceStoreTest {

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:nonce_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE runtime_internal_auth_nonce (
                  nonce VARCHAR(128) NOT NULL PRIMARY KEY,
                  caller VARCHAR(96) NOT NULL,
                  created_at TIMESTAMP NOT NULL
                )
                """);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TABLE IF EXISTS runtime_internal_auth_nonce");
    }

    @Test
    void maxEntriesOneRejectsImmediateReplay() {
        JdbcInternalAuthNonceStore store = new JdbcInternalAuthNonceStore(jdbc);
        long now = 1_700_000_000_000L;
        assertTrue(store.tryConsume("reachai-control-service", "nonce-a", now, 600, 1));
        assertFalse(store.tryConsume("reachai-control-service", "nonce-a", now + 1, 600, 1));
    }

    @Test
    void capacityFullRejectsNewButDoesNotAllowReplay() {
        JdbcInternalAuthNonceStore store = new JdbcInternalAuthNonceStore(jdbc);
        long now = 1_700_000_000_000L;
        assertTrue(store.tryConsume("reachai-control-service", "nonce-a", now, 600, 1));
        assertFalse(store.tryConsume("reachai-control-service", "nonce-b", now + 1, 600, 1));
        assertFalse(store.tryConsume("reachai-control-service", "nonce-a", now + 2, 600, 1));
    }

    @Test
    void concurrentSameNonceSucceedsExactlyOnce() throws Exception {
        JdbcInternalAuthNonceStore store = new JdbcInternalAuthNonceStore(jdbc);
        long now = 1_700_000_000_000L;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger successes = new AtomicInteger();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            tasks.add(() -> store.tryConsume("reachai-control-service", "race-nonce", now, 600, 1000));
        }
        List<Future<Boolean>> futures = pool.invokeAll(tasks);
        for (Future<Boolean> future : futures) {
            if (Boolean.TRUE.equals(future.get())) {
                successes.incrementAndGet();
            }
        }
        pool.shutdownNow();
        assertEquals(1, successes.get());
    }

    @Test
    void twoStoreInstancesShareDatabaseAntiReplay() {
        JdbcInternalAuthNonceStore a = new JdbcInternalAuthNonceStore(jdbc);
        JdbcInternalAuthNonceStore b = new JdbcInternalAuthNonceStore(new JdbcTemplate(dataSource));
        long now = 1_700_000_000_000L;
        assertTrue(a.tryConsume("reachai-control-service", "shared-nonce", now, 600, 100));
        assertFalse(b.tryConsume("reachai-control-service", "shared-nonce", now + 1, 600, 100));
    }

    @Test
    void expiredRowsAreCleanedAndCanBeReused() {
        JdbcInternalAuthNonceStore store = new JdbcInternalAuthNonceStore(jdbc);
        long now = 1_700_000_000_000L;
        assertTrue(store.tryConsume("reachai-control-service", "old", now, 10, 10));
        assertTrue(store.tryConsume("reachai-control-service", "old", now + 11_000L, 10, 10));
    }

    @Test
    void normalRequestUsesOneStatementAndDoesNotSweepOrCountSeparately() {
        JdbcTemplate observed = spy(jdbc);
        JdbcInternalAuthNonceStore store = new JdbcInternalAuthNonceStore(observed);
        long now = 1_700_000_000_000L;
        assertTrue(store.tryConsume("control", "first", now, 600, 100));
        clearInvocations(observed);
        assertTrue(store.tryConsume("control", "second", now + 1, 600, 100));
        verify(observed, times(1)).update(contains("INSERT INTO runtime_internal_auth_nonce"),
                eq("second"), eq("control"), any(Timestamp.class), any(Timestamp.class), eq(100));
        verify(observed, never()).update(startsWith("DELETE"), any(Timestamp.class));
        verify(observed, never()).queryForObject(anyString(), eq(Integer.class));
    }

    @Test
    void expiredReplayCanBeReusedBetweenSweepsWithoutEvictingLiveRows() {
        JdbcInternalAuthNonceStore store = new JdbcInternalAuthNonceStore(jdbc);
        long now = 1_700_000_000_000L;
        assertTrue(store.tryConsume("control", "live", now, 600, 2));
        jdbc.update("INSERT INTO runtime_internal_auth_nonce (nonce,caller,created_at) VALUES (?,?,?)",
                "expired", "control", new Timestamp(now - 601_000L));
        assertTrue(store.tryConsume("control", "expired", now + 1, 600, 2));
        assertFalse(store.tryConsume("control", "live", now + 2, 600, 2));
        assertFalse(store.tryConsume("control", "new", now + 3, 600, 2));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(1) FROM runtime_internal_auth_nonce", Integer.class));
    }

    @Test
    void atomicInsertRetriesRolledBackDeadlockAndStillRejectsReplay() {
        JdbcTemplate observed = spy(jdbc);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.getAndIncrement() == 0) throw new CannotAcquireLockException("deadlock",
                    new SQLException("deadlock", "40001", 1213));
            return call.callRealMethod();
        }).when(observed).update(contains("INSERT INTO runtime_internal_auth_nonce"),
                eq("retry"), eq("control"), any(Timestamp.class), any(Timestamp.class), eq(100));
        var store = new JdbcInternalAuthNonceStore(observed);
        long now = 1_700_000_000_000L;
        assertTrue(store.tryConsume("control", "retry", now, 600, 100));
        assertEquals(2, attempts.get());
        assertFalse(store.tryConsume("control", "retry", now + 1, 600, 100));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(1) FROM runtime_internal_auth_nonce", Integer.class));
    }

    @Test
    void permanentDeadlockHasBoundedRetriesAndNeverConsumesNonce() {
        JdbcTemplate observed = spy(jdbc);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            attempts.incrementAndGet();
            throw new CannotAcquireLockException("deadlock", new SQLException("deadlock", "40001", 1213));
        }).when(observed).update(contains("INSERT INTO runtime_internal_auth_nonce"),
                eq("retry"), eq("control"), any(Timestamp.class), any(Timestamp.class), eq(100));
        var store = new JdbcInternalAuthNonceStore(observed);
        assertThrows(CannotAcquireLockException.class, () -> store.tryConsume("control", "retry", 1_700_000_000_000L, 600, 100));
        assertEquals(3, attempts.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(1) FROM runtime_internal_auth_nonce", Integer.class));
    }

    @Test
    void callerTransactionIsRejectedBeforeAnyNonceStatement() {
        JdbcTemplate observed = spy(jdbc);
        var store = new JdbcInternalAuthNonceStore(observed);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.execute(status -> {
            assertFalse(store.tryConsume("control", "caller-tx", 1_700_000_000_000L, 600, 100));
            return null;
        });
        verifyNoInteractions(observed);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(1) FROM runtime_internal_auth_nonce", Integer.class));
    }

    @Test
    void lockWaitTimeoutIsNotRetriedBecauseStatementOutcomeIsNotAssumed() {
        JdbcTemplate observed = spy(jdbc);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            attempts.incrementAndGet();
            throw new CannotAcquireLockException("lock timeout", new SQLException("lock timeout", "HY000", 1205));
        }).when(observed).update(contains("INSERT INTO runtime_internal_auth_nonce"),
                eq("timeout"), eq("control"), any(Timestamp.class), any(Timestamp.class), eq(100));
        var store = new JdbcInternalAuthNonceStore(observed);
        assertThrows(CannotAcquireLockException.class, () -> store.tryConsume("control", "timeout", 1_700_000_000_000L, 600, 100));
        assertEquals(1, attempts.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(1) FROM runtime_internal_auth_nonce", Integer.class));
    }
}
