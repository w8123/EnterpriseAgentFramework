package com.enterprise.ai.control.mcp.application.employee;

import com.enterprise.ai.control.mcp.application.port.AiToolConnectionRepository;
import com.enterprise.ai.control.mcp.application.port.AiToolConnectionRepository.SearchQuery;
import com.enterprise.ai.control.mcp.application.port.McpEmployeeAccessRepository;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnectionStatus;
import com.enterprise.ai.control.mcp.domain.employee.McpEmployeeAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Administrator view of every employee's AI-tool connections, with forced disconnect. */
@Service
public class AiToolConnectionAdminService {

    private final AiToolConnectionRepository connectionRepository;
    private final McpEmployeeAccessRepository accessRepository;

    public AiToolConnectionAdminService(AiToolConnectionRepository connectionRepository,
                                        McpEmployeeAccessRepository accessRepository) {
        this.connectionRepository = connectionRepository;
        this.accessRepository = accessRepository;
    }

    public Page list(String projectCode, String state, String keyword, int limit, int offset) {
        LocalDateTime now = LocalDateTime.now();
        var page = connectionRepository.search(new SearchQuery(projectCode, state, keyword, limit, offset), now);
        Map<String, String> labels = systemLabels();
        return new Page(page.items().stream().map(connection -> view(connection, labels, now)).toList(),
                page.total(), limit, offset);
    }

    @Transactional
    public AdminConnectionView revoke(long connectionId) {
        connectionRepository.findById(connectionId).orElseThrow(() ->
                new McpDomainException("AI_TOOL_CONNECTION_NOT_FOUND", "connection not found"));
        LocalDateTime now = LocalDateTime.now();
        connectionRepository.revoke(connectionId, now);
        return view(connectionRepository.findById(connectionId).orElseThrow(), systemLabels(), now);
    }

    private Map<String, String> systemLabels() {
        return accessRepository.findEnabled().stream()
                .collect(Collectors.toMap(McpEmployeeAccess::projectCode, McpEmployeeAccess::label, (a, b) -> a));
    }

    private AdminConnectionView view(AiToolConnection connection, Map<String, String> labels, LocalDateTime now) {
        String state = connection.state() == AiToolConnectionStatus.ACTIVE && connection.expired(now)
                ? AiToolConnectionStatus.EXPIRED.name()
                : connection.state().name();
        return new AdminConnectionView(
                connection.id(),
                connection.tenantId(),
                connection.userName(),
                connection.appId(),
                connection.externalUserId(),
                connection.toolKind().name(),
                connection.keyPrefix(),
                state,
                connection.systems().stream()
                        .map(system -> new AiToolConnectionService.ConnectedSystemView(system.projectCode(),
                                labels.getOrDefault(system.projectCode(), system.projectCode())))
                        .toList(),
                connection.createdAt(),
                connection.lastUsedAt(),
                connection.expiresAt());
    }

    public record AdminConnectionView(Long id, String tenantId, String userName, String appId,
                                      String externalUserId, String toolKind, String keyPrefix, String state,
                                      List<AiToolConnectionService.ConnectedSystemView> systems,
                                      LocalDateTime createdAt, LocalDateTime lastUsedAt,
                                      LocalDateTime expiresAt) {
    }

    public record Page(List<AdminConnectionView> items, long total, int limit, int offset) {
    }
}
