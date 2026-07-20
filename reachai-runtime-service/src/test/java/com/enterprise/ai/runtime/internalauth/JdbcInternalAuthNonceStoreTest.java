package com.enterprise.ai.runtime.internalauth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
