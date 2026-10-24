package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AiToolConnectionRepository {

    /** Inserts a new connection with its key hash and ticked systems. */
    AiToolConnection create(AiToolConnection connection, String keyHash);

    Optional<AiToolConnection> findById(long id);

    Optional<AiToolConnection> findByKeyHash(String keyHash);

    /** The owner's connections, newest first. */
    List<AiToolConnection> findByOwner(String tenantId, long businessUserId);

    long countActiveByOwner(String tenantId, long businessUserId, LocalDateTime now);

    void revoke(long id, LocalDateTime now);

    void expire(long id, LocalDateTime now);

    void touchLastUsed(long id, LocalDateTime now);

    /** Admin view across all employees, newest first. */
    SearchPage search(SearchQuery query, LocalDateTime now);

    /**
     * @param state ACTIVE / REVOKED / EXPIRED as the employee sees it: an ACTIVE row past its expiry counts as EXPIRED
     * @param keyword matches the employee name or business-system account id
     */
    record SearchQuery(String projectCode, String state, String keyword, int limit, int offset) {
    }

    record SearchPage(List<AiToolConnection> items, long total) {
    }
}
