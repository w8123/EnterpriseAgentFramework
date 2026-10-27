package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.mcp.domain.employee.McpWriteApproval;
import com.enterprise.ai.control.mcp.domain.employee.McpWriteApprovalStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface McpWriteApprovalRepository {

    McpWriteApproval create(McpWriteApproval approval);

    Optional<McpWriteApproval> findById(long id);

    /** An open request from the same connection for the same tool and arguments, if any. */
    Optional<McpWriteApproval> findOpenDuplicate(long connectionId, String toolName, String argumentsHash,
                                                 LocalDateTime now);

    long countOpenByOwner(String tenantId, long businessUserId, LocalDateTime now);

    /** The employee's requests across all their connections, newest first. */
    List<McpWriteApproval> findByOwner(String tenantId, long businessUserId, int limit);

    /**
     * Moves PENDING (and not past its deadline) to {@code next} with the decision; false when someone
     * else decided first or it lapsed.
     */
    boolean decide(long id, McpWriteApprovalStatus next, String decidedBy, String comment, LocalDateTime now);

    /** Records how the approved execution ended (EXECUTING → SUCCEEDED / FAILED). */
    void finish(long id, McpWriteApprovalStatus outcome, String resultJson, String errorCode, String runId,
                String traceId, LocalDateTime now);

    /** Marks PENDING rows past their deadline as EXPIRED. */
    int expireLapsed(LocalDateTime now);

    SearchPage search(SearchQuery query, LocalDateTime now);

    long countPending(LocalDateTime now);

    /**
     * @param state PENDING / EXECUTING / SUCCEEDED / FAILED / REJECTED / EXPIRED as people see it
     * @param keyword matches the employee name, account id or tool name
     */
    record SearchQuery(String projectCode, String state, String keyword, int limit, int offset) {
    }

    record SearchPage(List<McpWriteApproval> items, long total) {
    }
}
