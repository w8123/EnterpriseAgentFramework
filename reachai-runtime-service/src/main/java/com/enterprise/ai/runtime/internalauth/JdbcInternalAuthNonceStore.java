package com.enterprise.ai.runtime.internalauth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/** Database primary key preserves replay protection across replicas; no live nonce is evicted. */
@Component
public class JdbcInternalAuthNonceStore implements InternalAuthNonceStore {
    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong nextCleanupMillis = new AtomicLong(Long.MIN_VALUE);

    public JdbcInternalAuthNonceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean tryConsume(String caller, String nonce, long nowMillis, long ttlSeconds, int maxEntries) {
        // Verification precedes business transactions; a rollback must never release a consumed nonce.
        if (TransactionSynchronizationManager.isActualTransactionActive()) return false;
        if (caller == null || nonce == null || caller.isBlank() || nonce.isBlank()
                || ttlSeconds < 1 || maxEntries < 1) return false;
        Timestamp now = Timestamp.from(Instant.ofEpochMilli(nowMillis));
        Timestamp expireBefore = Timestamp.from(Instant.ofEpochMilli(nowMillis).minusSeconds(ttlSeconds));
        cleanup(nowMillis, ttlSeconds, expireBefore);
        try {
            return insert(caller.trim(), nonce.trim(), now, expireBefore, maxEntries);
        } catch (DuplicateKeyException replay) {
            // An expired nonce may be reused before the periodic sweep, never a live one.
            if (execute(() -> jdbcTemplate.update("DELETE FROM runtime_internal_auth_nonce WHERE nonce = ? AND created_at < ?",
                    nonce.trim(), expireBefore)) == 0) return false;
            try { return insert(caller.trim(), nonce.trim(), now, expireBefore, maxEntries); }
            catch (DuplicateKeyException concurrentReplay) { return false; }
        }
    }

    private boolean insert(String caller, String nonce, Timestamp now, Timestamp expireBefore, int maxEntries) {
        // COUNT forces materialization; the configured REPEATABLE_READ locks the live range through insert.
        // MySQL 5.7 requires innodb_locks_unsafe_for_binlog=OFF (the server default).
        return execute(() -> jdbcTemplate.update("""
                INSERT INTO runtime_internal_auth_nonce (nonce, caller, created_at)
                SELECT ?, ?, ? FROM
                  (SELECT COUNT(1) AS live_count FROM runtime_internal_auth_nonce WHERE created_at >= ?) capacity
                WHERE capacity.live_count < ?
                """, nonce, caller, now, expireBefore, maxEntries)) == 1;
    }

    private int execute(IntSupplier statement) {
        for (int attempt = 0; ; attempt++) {
            try { return statement.getAsInt(); }
            catch (PessimisticLockingFailureException contention) {
                // MySQL 1213 rolls back the atomic statement. Retry only outside a caller transaction.
                if (!(contention.getMostSpecificCause() instanceof SQLException sql) || sql.getErrorCode() != 1213
                        || attempt >= 2 || TransactionSynchronizationManager.isActualTransactionActive()) throw contention;
                try { Thread.sleep(5L * (attempt + 1)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw contention; }
            }
        }
    }

    private void cleanup(long nowMillis, long ttlSeconds, Timestamp expireBefore) {
        long due = nextCleanupMillis.get();
        if (nowMillis < due || !nextCleanupMillis.compareAndSet(due,
                nowMillis + Math.min(ttlSeconds, 60L) * 1000L)) return;
        try { execute(() -> jdbcTemplate.update("DELETE FROM runtime_internal_auth_nonce WHERE created_at < ?", expireBefore)); }
        catch (RuntimeException failed) { nextCleanupMillis.set(Long.MIN_VALUE); throw failed; }
    }
}
