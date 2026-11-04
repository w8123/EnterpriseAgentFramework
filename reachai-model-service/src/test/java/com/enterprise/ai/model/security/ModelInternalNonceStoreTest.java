package com.enterprise.ai.model.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ModelInternalNonceStoreTest {
    @Test
    void replicasAndRestartShareReplayProtectionAndDatabaseFailureRejectsCalls() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE model_internal_auth_nonce (nonce VARCHAR(128) PRIMARY KEY, caller VARCHAR(64), created_at TIMESTAMP)");
        Duration ttl = Duration.ofMinutes(10);
        assertTrue(new ModelInternalNonceStore(jdbc).tryConsume("control", "nonce-1", ttl));
        assertFalse(new ModelInternalNonceStore(jdbc).tryConsume("control", "nonce-1", ttl));
        jdbc.update("UPDATE model_internal_auth_nonce SET created_at = ?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(601)));
        assertTrue(new ModelInternalNonceStore(jdbc).tryConsume("control", "nonce-1", ttl));
        jdbc.execute("DROP TABLE model_internal_auth_nonce");
        assertFalse(new ModelInternalNonceStore(jdbc).tryConsume("control", "nonce-2", ttl));
    }
}
