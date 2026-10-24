package com.enterprise.ai.control.mcp.application.employee;

import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.BusinessUserSyncService;
import com.enterprise.ai.control.identity.BusinessUserSyncService.BusinessSystemAccount;
import com.enterprise.ai.control.mcp.application.port.AiToolConnectionRepository;
import com.enterprise.ai.control.mcp.application.port.McpCallAuditRepository;
import com.enterprise.ai.control.mcp.application.port.McpEmployeeAccessRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.application.port.McpRuntimeExecutionGateway;
import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService.McpToolCallResult;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection.ConnectedSystem;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnectionStatus;
import com.enterprise.ai.control.mcp.domain.employee.McpEmployeeAccess;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationRevision;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationStatus;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * MCP protocol semantics for an employee's personal AI-tool connection. The
 * connection names the employee and the ticked business systems; every call is
 * re-checked against the employee's current account and roles in that system,
 * then executed in Runtime as that employee.
 */
@Service
public class McpEmployeeProtocolService {

    private final AiToolConnectionRepository connectionRepository;
    private final McpEmployeeAccessRepository accessRepository;
    private final McpPublicationRepository publicationRepository;
    private final BusinessUserSyncService businessUserSyncService;
    private final ControlToolAclDecisionService aclDecisionService;
    private final McpRuntimeExecutionGateway executionGateway;
    private final McpCallAuditRepository callAuditRepository;
    private final ObjectMapper objectMapper;

    public McpEmployeeProtocolService(AiToolConnectionRepository connectionRepository,
                                      McpEmployeeAccessRepository accessRepository,
                                      McpPublicationRepository publicationRepository,
                                      BusinessUserSyncService businessUserSyncService,
                                      ControlToolAclDecisionService aclDecisionService,
                                      McpRuntimeExecutionGateway executionGateway,
                                      McpCallAuditRepository callAuditRepository,
                                      ObjectMapper objectMapper) {
        this.connectionRepository = connectionRepository;
        this.accessRepository = accessRepository;
        this.publicationRepository = publicationRepository;
        this.businessUserSyncService = businessUserSyncService;
        this.aclDecisionService = aclDecisionService;
        this.executionGateway = executionGateway;
        this.callAuditRepository = callAuditRepository;
        this.objectMapper = objectMapper;
    }

    /** Resolves the personal key to the employee and the systems still usable right now. */
    public EmployeeSession authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()
                || !bearerToken.startsWith(AiToolConnectionService.KEY_PREFIX)) {
            throw new McpDomainException("MCP_CLIENT_UNAUTHENTICATED", "a personal connection key is required");
        }
        AiToolConnection connection = connectionRepository
                .findByKeyHash(AiToolConnectionService.sha256Hex(bearerToken))
                .orElseThrow(() -> new McpDomainException("MCP_CLIENT_UNAUTHENTICATED",
                        "the personal connection key is invalid"));
        LocalDateTime now = LocalDateTime.now();
        if (connection.state() != AiToolConnectionStatus.ACTIVE) {
            throw new McpDomainException("MCP_CLIENT_" + connection.state().name(),
                    "this connection was " + connection.state().name().toLowerCase(Locale.ROOT)
                            + "; create a new one in the business system");
        }
        if (connection.expired(now)) {
            connectionRepository.expire(connection.id(), now);
            throw new McpDomainException("MCP_CLIENT_EXPIRED",
                    "this connection has expired; create a new one in the business system");
        }
        List<SystemSession> systems = new ArrayList<>();
        for (ConnectedSystem system : connection.systems()) {
            usableSystem(connection, system).ifPresent(systems::add);
        }
        if (systems.isEmpty()) {
            throw new McpDomainException("MCP_CLIENT_DISABLED",
                    "none of the connected business systems is available to this employee now");
        }
        connectionRepository.touchLastUsed(connection.id(), now);
        return new EmployeeSession(connection, systems, exposedNames(systems));
    }

    /** tools/list: the systems' live revisions, IRREVERSIBLE tools never exposed to a personal connection. */
    public List<Map<String, Object>> visibleTools(EmployeeSession session) {
        List<Map<String, Object>> tools = new ArrayList<>();
        session.exposed().forEach((name, target) -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", name);
            String description = target.tool().description();
            view.put("description", session.systems().size() > 1
                    ? "【" + target.system().access().label() + "】" + description
                    : description);
            view.put("inputSchema", schema(target.tool().inputSchemaJson()));
            Map<String, Object> annotations = new LinkedHashMap<>();
            annotations.put("riskLevel", target.tool().riskLevel() == null ? "WRITE" : target.tool().riskLevel());
            annotations.put("sourceKind", target.tool().sourceKind().name());
            annotations.put("businessSystem", target.system().access().label());
            view.put("annotations", annotations);
            tools.add(view);
        });
        return tools;
    }

    /** tools/call: visibility → employee's call permission in that system → execution as the employee. */
    public EmployeeCallResult callTool(EmployeeSession session, String toolName, Map<String, Object> arguments) {
        if (toolName == null || toolName.isBlank()) {
            throw new McpDomainException("MCP_TOOL_NAME_REQUIRED", "tools/call params.name is required");
        }
        ExposedTool target = session.exposed().get(toolName.trim());
        if (target == null) {
            throw new McpDomainException("MCP_TOOL_NOT_VISIBLE",
                    "tool is not available through this connection: " + toolName);
        }
        SystemSession system = target.system();
        String decision = aclDecisionService.decide(system.account().roles(), system.access().projectId(),
                system.access().projectCode(), "TOOL", target.tool().sourceRef());
        if (!ControlToolAclDecisionService.DECISION_ALLOW.equals(decision)) {
            throw new McpDomainException("MCP_TOOL_ACL_DENIED",
                    "你在" + system.access().label() + "没有使用这个能力的权限（调用权限 " + decision + "）");
        }
        AiToolConnection connection = session.connection();
        McpRuntimeExecutionGateway.ExecutionOutcome outcome;
        try {
            outcome = executionGateway.executeAsEmployee(new McpRuntimeExecutionGateway.EmployeeToolExecutionCommand(
                    target.tool().sourceKind().name(),
                    target.tool().sourceRef(),
                    target.tool().workflowVersionId(),
                    target.tool().name(),
                    arguments == null ? Map.of() : arguments,
                    connection.id(),
                    connection.toolKind().name(),
                    system.account().externalUserId(),
                    system.access().projectId(),
                    system.access().projectCode(),
                    connection.tenantId(),
                    system.publication().id(),
                    system.revision().revisionNo(),
                    target.tool().capabilityContractHash()));
        } catch (RuntimeException boundaryFailure) {
            outcome = new McpRuntimeExecutionGateway.ExecutionOutcome(
                    false, "MCP_RUNTIME_EXECUTION_BOUNDARY_FAILED", Map.of(),
                    null, null, "MCP_RUNTIME_EXECUTION_BOUNDARY_FAILED");
        }
        McpToolCallResult result = new McpToolCallResult(outcome.success(), outcome.output(),
                outcome.runId(), outcome.traceId(),
                outcome.success() ? null
                        : (outcome.errorCode() == null ? "MCP_TOOL_EXECUTION_FAILED" : outcome.errorCode()));
        return new EmployeeCallResult(result, system);
    }

    /** Append-only audit entry; the employee and AI tool are recorded alongside the system. */
    public void audit(EmployeeAuditRequest request) {
        AiToolConnection connection = request.session() == null ? null : request.session().connection();
        SystemSession system = request.system();
        callAuditRepository.append(new McpCallAuditRepository.Entry(
                "OUTBOUND",
                system == null ? null : system.publication().id(),
                null,
                connection == null ? null : bounded(connection.userName(), 128),
                null,
                bounded(request.method() == null ? "unknown" : request.method(), 64),
                bounded(request.toolName(), 128),
                null,
                system == null ? null : bounded(system.access().projectCode(), 96),
                null,
                connection == null ? null : bounded(connection.tenantId(), 96),
                request.success(),
                request.latencyMs(),
                bounded(request.errorCategory(), 32),
                request.requestBody(),
                request.responseBody(),
                bounded(request.errorMessage(), 2000),
                bounded(request.traceId(), 64),
                bounded(request.runId(), 64),
                bounded(request.remoteIp(), 64),
                LocalDateTime.now(),
                connection == null ? null : connection.id(),
                system == null ? (connection == null ? null : bounded(connection.externalUserId(), 128))
                        : bounded(system.account().externalUserId(), 128),
                connection == null ? null : connection.toolKind().name()));
    }

    private Optional<SystemSession> usableSystem(AiToolConnection connection, ConnectedSystem system) {
        Optional<McpEmployeeAccess> access = accessRepository.findByProjectCode(system.projectCode())
                .filter(McpEmployeeAccess::enabled);
        if (access.isEmpty()) {
            return Optional.empty();
        }
        Optional<McpPublication> publication = publicationRepository.findById(access.get().publicationId())
                .filter(found -> found.state() == McpPublicationStatus.PUBLISHED && found.currentRevisionId() != null);
        if (publication.isEmpty()) {
            return Optional.empty();
        }
        Optional<McpPublicationRevision> revision = publicationRepository.findRevision(
                publication.get().id(), publication.get().currentRevisionId());
        Optional<BusinessSystemAccount> account = businessUserSyncService.activeAccount(
                connection.tenantId(), connection.businessUserId(), system.projectCode());
        if (revision.isEmpty() || account.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SystemSession(access.get(), publication.get(), revision.get(), account.get()));
    }

    /** Tool names stay as published unless two systems share one; then both get the system prefix. */
    private static Map<String, ExposedTool> exposedNames(List<SystemSession> systems) {
        Map<String, Integer> counts = new HashMap<>();
        for (SystemSession system : systems) {
            for (McpToolProjection tool : system.revision().tools()) {
                if (!irreversible(tool)) {
                    counts.merge(tool.name(), 1, Integer::sum);
                }
            }
        }
        Map<String, ExposedTool> exposed = new LinkedHashMap<>();
        for (SystemSession system : systems) {
            for (McpToolProjection tool : system.revision().tools()) {
                if (irreversible(tool)) {
                    continue;
                }
                String name = counts.get(tool.name()) > 1
                        ? prefix(system.access().projectCode()) + "__" + tool.name()
                        : tool.name();
                exposed.put(name, new ExposedTool(tool, system));
            }
        }
        return exposed;
    }

    private static String prefix(String projectCode) {
        return projectCode.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static boolean irreversible(McpToolProjection tool) {
        return "IRREVERSIBLE".equalsIgnoreCase(tool.riskLevel() == null ? "" : tool.riskLevel());
    }

    private Object schema(String inputSchemaJson) {
        try {
            var schema = inputSchemaJson == null ? null : objectMapper.readTree(inputSchemaJson);
            if (schema == null || !schema.isObject()) {
                throw new McpDomainException("MCP_PUBLICATION_REVISION_INVALID", "the frozen tool schema is invalid");
            }
            return schema;
        } catch (McpDomainException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new McpDomainException("MCP_PUBLICATION_REVISION_INVALID", "the frozen tool schema is invalid");
        }
    }

    private static String bounded(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maximumLength ? trimmed : trimmed.substring(0, maximumLength);
    }

    /** One connected business system usable in this request, with the employee's account there. */
    public record SystemSession(McpEmployeeAccess access, McpPublication publication,
                                McpPublicationRevision revision, BusinessSystemAccount account) {
    }

    public record ExposedTool(McpToolProjection tool, SystemSession system) {
    }

    public record EmployeeSession(AiToolConnection connection, List<SystemSession> systems,
                                  Map<String, ExposedTool> exposed) {
    }

    public record EmployeeCallResult(McpToolCallResult result, SystemSession system) {
    }

    public record EmployeeAuditRequest(EmployeeSession session, SystemSession system, String method,
                                       String toolName, boolean success, Long latencyMs,
                                       String errorCategory, String requestBody, String responseBody,
                                       String errorMessage, String traceId, String runId, String remoteIp) {
    }
}
