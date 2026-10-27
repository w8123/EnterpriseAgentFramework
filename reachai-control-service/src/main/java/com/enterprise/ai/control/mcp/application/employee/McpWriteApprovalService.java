package com.enterprise.ai.control.mcp.application.employee;

import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import com.enterprise.ai.control.mcp.application.employee.McpEmployeeProtocolService.ApprovedExecution;
import com.enterprise.ai.control.mcp.application.port.McpWriteApprovalRepository;
import com.enterprise.ai.control.mcp.application.port.McpWriteApprovalRepository.SearchQuery;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.employee.McpWriteApproval;
import com.enterprise.ai.control.mcp.domain.employee.McpWriteApprovalStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 写操作审批: approvers see what an employee's AI tool asked to change and let it run
 * as that employee once, or turn it down. A request is decided at most once.
 */
@Service
public class McpWriteApprovalService {

    private static final int COMMENT_LIMIT = 500;

    private final McpWriteApprovalRepository repository;
    private final McpEmployeeProtocolService protocolService;
    private final ObjectMapper objectMapper;

    public McpWriteApprovalService(McpWriteApprovalRepository repository,
                                   McpEmployeeProtocolService protocolService,
                                   ObjectMapper objectMapper) {
        this.repository = repository;
        this.protocolService = protocolService;
        this.objectMapper = objectMapper;
    }

    public Page list(String projectCode, String state, String keyword, int limit, int offset,
                     boolean includeArguments) {
        LocalDateTime now = LocalDateTime.now();
        repository.expireLapsed(now);
        var page = repository.search(new SearchQuery(projectCode, state, keyword, limit, offset), now);
        return new Page(page.items().stream().map(item -> view(item, now, includeArguments)).toList(),
                page.total(), repository.countPending(now), limit, offset);
    }

    public ApprovalView get(long id, boolean includeArguments) {
        LocalDateTime now = LocalDateTime.now();
        return view(require(id), now, includeArguments);
    }

    public ApprovalView approve(long id, String approver, String comment) {
        LocalDateTime now = LocalDateTime.now();
        McpWriteApproval approval = require(id);
        if (!repository.decide(id, McpWriteApprovalStatus.EXECUTING, approver, comment(comment), now)) {
            throw notPending(id, now);
        }
        ApprovedExecution execution;
        try {
            execution = protocolService.executeApproved(approval);
        } catch (RuntimeException unexpected) {
            execution = new ApprovedExecution(false, Map.of(), "MCP_APPROVAL_EXECUTION_FAILED",
                    "执行时出现异常", null, null);
        }
        Map<String, Object> output = execution.refusal() == null ? execution.output()
                : Map.of("reason", execution.refusal());
        repository.finish(id, execution.success() ? McpWriteApprovalStatus.SUCCEEDED : McpWriteApprovalStatus.FAILED,
                protocolService.resultJson(output), execution.success() ? null : execution.errorCode(),
                execution.runId(), execution.traceId(), LocalDateTime.now());
        return view(require(id), LocalDateTime.now(), true);
    }

    public ApprovalView reject(long id, String approver, String comment) {
        LocalDateTime now = LocalDateTime.now();
        require(id);
        if (!repository.decide(id, McpWriteApprovalStatus.REJECTED, approver, comment(comment), now)) {
            throw notPending(id, now);
        }
        return view(require(id), now, true);
    }

    /** The employee's own requests, for 「我的审批」 inside the business system. */
    public List<Map<String, Object>> mine(EmployeeOwner owner) {
        LocalDateTime now = LocalDateTime.now();
        return repository.findByOwner(owner.tenantId(), owner.businessUserId(), 20).stream()
                .map(approval -> protocolService.employeeView(approval, now))
                .toList();
    }

    private McpWriteApproval require(long id) {
        return repository.findById(id).orElseThrow(() ->
                new McpDomainException("MCP_APPROVAL_NOT_FOUND", "审批单不存在"));
    }

    private McpDomainException notPending(long id, LocalDateTime now) {
        McpWriteApprovalStatus state = require(id).visibleState(now);
        return new McpDomainException("MCP_APPROVAL_NOT_PENDING", switch (state) {
            case EXPIRED -> "审批单已过期，不能再处理";
            case REJECTED -> "审批单已被驳回";
            default -> "审批单已被处理";
        });
    }

    private static String comment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String trimmed = comment.trim();
        return trimmed.length() <= COMMENT_LIMIT ? trimmed : trimmed.substring(0, COMMENT_LIMIT);
    }

    private ApprovalView view(McpWriteApproval approval, LocalDateTime now, boolean includeArguments) {
        return new ApprovalView(approval.id(), approval.visibleState(now).name(), approval.tenantId(),
                approval.connectionId(), approval.userName(), approval.externalUserId(), approval.toolKind(),
                approval.projectCode(), approval.systemName(), approval.publicationId(), approval.revisionNo(),
                approval.toolName(), approval.toolDescription(), approval.sourceKind(), approval.sourceRef(),
                approval.riskLevel(),
                includeArguments ? parse(approval.argumentsJson()) : null,
                approval.decidedBy(), approval.decidedAt(), approval.decisionComment(), approval.executedAt(),
                includeArguments && approval.resultJson() != null ? parse(approval.resultJson()) : null,
                approval.errorCode(), approval.runId(), approval.traceId(), approval.expiresAt(),
                approval.createdAt());
    }

    private Object parse(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception invalid) {
            return json;
        }
    }

    public record ApprovalView(Long id, String state, String tenantId, long connectionId, String userName,
                               String externalUserId, String toolKind, String projectCode, String systemName,
                               long publicationId, int revisionNo, String toolName, String toolDescription,
                               String sourceKind, String sourceRef, String riskLevel, Object arguments,
                               String decidedBy, LocalDateTime decidedAt, String decisionComment,
                               LocalDateTime executedAt, Object result, String errorCode, String runId,
                               String traceId, LocalDateTime expiresAt, LocalDateTime createdAt) {
    }

    public record Page(List<ApprovalView> items, long total, long pendingCount, int limit, int offset) {
    }
}
