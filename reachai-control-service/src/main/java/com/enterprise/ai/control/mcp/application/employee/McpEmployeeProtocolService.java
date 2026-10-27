package com.enterprise.ai.control.mcp.application.employee;

import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.identity.BusinessUserSyncService;
import com.enterprise.ai.control.identity.BusinessUserSyncService.BusinessSystemAccount;
import com.enterprise.ai.control.mcp.application.port.AiToolConnectionRepository;
import com.enterprise.ai.control.mcp.application.port.McpCallAuditRepository;
import com.enterprise.ai.control.mcp.application.port.McpEmployeeAccessRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.application.port.McpRuntimeExecutionGateway;
import com.enterprise.ai.control.mcp.application.port.McpWriteApprovalRepository;
import com.enterprise.ai.control.mcp.application.protocol.McpProtocolApplicationService.McpToolCallResult;
import com.enterprise.ai.control.mcp.application.schema.McpDottedArgumentShape;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnection.ConnectedSystem;
import com.enterprise.ai.control.mcp.domain.employee.AiToolConnectionStatus;
import com.enterprise.ai.control.mcp.domain.employee.McpEmployeeAccess;
import com.enterprise.ai.control.mcp.domain.employee.McpWriteApproval;
import com.enterprise.ai.control.mcp.domain.employee.McpWriteApprovalStatus;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationRevision;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationStatus;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * MCP protocol semantics for an employee's personal AI-tool connection. The
 * connection names the employee and the ticked business systems; every call is
 * re-checked against the employee's current account and roles in that system,
 * then executed in Runtime as that employee.
 */
@Service
public class McpEmployeeProtocolService {

    /** Built-in tool listed next to write tools that wait for approval. */
    public static final String APPROVAL_STATUS_TOOL = "reachai_approval_status";
    static final Duration APPROVAL_TTL = Duration.ofHours(24);
    static final int MAX_OPEN_APPROVALS_PER_EMPLOYEE = 20;
    private static final int RESULT_JSON_LIMIT = 16_000;

    private final AiToolConnectionRepository connectionRepository;
    private final McpEmployeeAccessRepository accessRepository;
    private final McpPublicationRepository publicationRepository;
    private final BusinessUserSyncService businessUserSyncService;
    private final ControlToolAclDecisionService aclDecisionService;
    private final McpRuntimeExecutionGateway executionGateway;
    private final McpCallAuditRepository callAuditRepository;
    private final McpWriteApprovalRepository approvalRepository;
    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalMapper;

    public McpEmployeeProtocolService(AiToolConnectionRepository connectionRepository,
                                      McpEmployeeAccessRepository accessRepository,
                                      McpPublicationRepository publicationRepository,
                                      BusinessUserSyncService businessUserSyncService,
                                      ControlToolAclDecisionService aclDecisionService,
                                      McpRuntimeExecutionGateway executionGateway,
                                      McpCallAuditRepository callAuditRepository,
                                      McpWriteApprovalRepository approvalRepository,
                                      ObjectMapper objectMapper) {
        this.connectionRepository = connectionRepository;
        this.accessRepository = accessRepository;
        this.publicationRepository = publicationRepository;
        this.businessUserSyncService = businessUserSyncService;
        this.aclDecisionService = aclDecisionService;
        this.executionGateway = executionGateway;
        this.callAuditRepository = callAuditRepository;
        this.approvalRepository = approvalRepository;
        this.objectMapper = objectMapper;
        this.canonicalMapper = objectMapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
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

    /**
     * tools/list: the systems' live revisions, IRREVERSIBLE tools never exposed to a personal connection.
     * Write tools that wait for approval say so, and the approval-status tool is listed with them.
     */
    public List<Map<String, Object>> visibleTools(EmployeeSession session) {
        List<Map<String, Object>> tools = new ArrayList<>();
        boolean anyApproval = false;
        for (Map.Entry<String, ExposedTool> entry : session.exposed().entrySet()) {
            ExposedTool target = entry.getValue();
            boolean approval = needsApproval(target);
            anyApproval |= approval;
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", entry.getKey());
            String description = target.tool().description();
            if (session.systems().size() > 1) {
                description = "【" + target.system().access().label() + "】" + description;
            }
            if (approval) {
                description = description + "（写操作：调用后先提交审批，审批人通过后才会以你的身份执行；用 "
                        + APPROVAL_STATUS_TOOL + " 查询结果）";
            }
            view.put("description", description);
            view.put("inputSchema", schema(target.tool().inputSchemaJson()));
            Map<String, Object> annotations = new LinkedHashMap<>();
            annotations.put("riskLevel", riskLevel(target.tool()));
            annotations.put("sourceKind", target.tool().sourceKind().name());
            annotations.put("businessSystem", target.system().access().label());
            if (approval) {
                annotations.put("requiresApproval", true);
            }
            view.put("annotations", annotations);
            tools.add(view);
        }
        if (anyApproval && !session.exposed().containsKey(APPROVAL_STATUS_TOOL)) {
            tools.add(approvalStatusTool());
        }
        return tools;
    }

    private static Map<String, Object> approvalStatusTool() {
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("type", "integer");
        id.put("description", "审批单号；不填则列出最近 10 张");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("approvalId", id));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", APPROVAL_STATUS_TOOL);
        view.put("description", "查询你提交的写操作审批：是否已通过、驳回原因、执行结果。");
        view.put("inputSchema", schema);
        view.put("annotations", Map.of("riskLevel", "READ", "sourceKind", "BUILT_IN"));
        return view;
    }

    /** tools/call: visibility → employee's call permission in that system → execution as the employee. */
    public EmployeeCallResult callTool(EmployeeSession session, String toolName, Map<String, Object> arguments) {
        if (toolName == null || toolName.isBlank()) {
            throw new McpDomainException("MCP_TOOL_NAME_REQUIRED", "tools/call params.name is required");
        }
        ExposedTool target = session.exposed().get(toolName.trim());
        if (target == null && APPROVAL_STATUS_TOOL.equals(toolName.trim())
                && session.exposed().values().stream().anyMatch(McpEmployeeProtocolService::needsApproval)) {
            return new EmployeeCallResult(approvalStatus(session, arguments), null);
        }
        if (target == null) {
            throw new McpDomainException("MCP_TOOL_NOT_VISIBLE",
                    "tool is not available through this connection: " + toolName);
        }
        SystemSession system = target.system();
        requireAllowed(system, target.tool());
        if (needsApproval(target)) {
            return new EmployeeCallResult(submitForApproval(session, toolName.trim(), target, arguments), system);
        }
        return new EmployeeCallResult(execute(session.connection(), system, target.tool(), arguments), system);
    }

    /**
     * Runs an approved request as the employee, after checking again that the connection, the
     * employee's account, the call permission and the tool contract are what they were.
     */
    public ApprovedExecution executeApproved(McpWriteApproval approval) {
        LocalDateTime now = LocalDateTime.now();
        Optional<AiToolConnection> connection = connectionRepository.findById(approval.connectionId())
                .filter(found -> found.usable(now));
        if (connection.isEmpty()) {
            return ApprovedExecution.refused("MCP_APPROVAL_CONNECTION_INACTIVE",
                    "员工的 AI 工具连接已断开或过期");
        }
        Optional<SystemSession> system = usableSystem(connection.get(),
                new ConnectedSystem(approval.projectCode(), approval.publicationId()));
        if (system.isEmpty()) {
            return ApprovedExecution.refused("MCP_APPROVAL_SYSTEM_UNAVAILABLE",
                    "员工在" + firstText(approval.systemName(), approval.projectCode()) + "已不可用（账号停用或系统已关闭员工使用）");
        }
        Optional<McpToolProjection> tool = system.get().revision().tools().stream()
                .filter(candidate -> !irreversible(candidate)
                        && candidate.sourceKind().name().equals(approval.sourceKind())
                        && candidate.sourceRef().equals(approval.sourceRef())
                        && Objects.equals(candidate.capabilityContractHash(), approval.capabilityContractHash()))
                .findFirst();
        if (tool.isEmpty()) {
            return ApprovedExecution.refused("MCP_APPROVAL_TOOL_CHANGED", "这个能力已下线或合同已变化，请让员工重新提交");
        }
        String decision = aclDecisionService.decide(system.get().account().roles(), system.get().access().projectId(),
                system.get().access().projectCode(), "TOOL", tool.get().sourceRef());
        if (!ControlToolAclDecisionService.DECISION_ALLOW.equals(decision)) {
            return ApprovedExecution.refused("MCP_APPROVAL_ACL_DENIED", "员工现在没有使用这个能力的权限");
        }
        long startedAt = System.nanoTime();
        Map<String, Object> arguments = parseObject(approval.argumentsJson());
        McpToolCallResult result = execute(connection.get(), system.get(), tool.get(), arguments);
        audit(new EmployeeAuditRequest(new EmployeeSession(connection.get(), List.of(system.get()), Map.of()),
                system.get(), "approval/execute", approval.toolName(), result.success(),
                (System.nanoTime() - startedAt) / 1_000_000L,
                result.success() ? null : "RUNTIME",
                bounded(toJson(Map.of("approvalId", approval.id(), "arguments", arguments)), RESULT_JSON_LIMIT),
                bounded(toJson(result.output()), RESULT_JSON_LIMIT),
                result.errorCode(), result.traceId(), result.runId(), null));
        return new ApprovedExecution(result.success(), result.output(), result.errorCode(), null,
                result.runId(), result.traceId());
    }

    private void requireAllowed(SystemSession system, McpToolProjection tool) {
        String decision = aclDecisionService.decide(system.account().roles(), system.access().projectId(),
                system.access().projectCode(), "TOOL", tool.sourceRef());
        if (!ControlToolAclDecisionService.DECISION_ALLOW.equals(decision)) {
            throw new McpDomainException("MCP_TOOL_ACL_DENIED",
                    "你在" + system.access().label() + "没有使用这个能力的权限（调用权限 " + decision + "）");
        }
    }

    private McpToolCallResult submitForApproval(EmployeeSession session, String exposedName, ExposedTool target,
                                                Map<String, Object> arguments) {
        AiToolConnection connection = session.connection();
        SystemSession system = target.system();
        LocalDateTime now = LocalDateTime.now();
        // Frozen in the shape the tool receives, so approvers read the same arguments that will run.
        Map<String, Object> frozen = arguments == null ? Map.of() : shapedArguments(target.tool(), arguments);
        String argumentsJson = canonicalJson(frozen);
        String argumentsHash = AiToolConnectionService.sha256Hex(argumentsJson);
        Optional<McpWriteApproval> duplicate = approvalRepository.findOpenDuplicate(
                connection.id(), exposedName, argumentsHash, now);
        if (duplicate.isPresent()) {
            return pendingResult(duplicate.get(), true);
        }
        if (approvalRepository.countOpenByOwner(connection.tenantId(), connection.businessUserId(), now)
                >= MAX_OPEN_APPROVALS_PER_EMPLOYEE) {
            return refusal("MCP_APPROVAL_TOO_MANY_PENDING",
                    "你已有 " + MAX_OPEN_APPROVALS_PER_EMPLOYEE + " 张写操作在等待审批，请等处理后再提交");
        }
        McpToolProjection tool = target.tool();
        McpWriteApproval created = approvalRepository.create(new McpWriteApproval(null,
                connection.tenantId(), connection.id(), connection.businessUserId(),
                system.account().externalUserId(),
                firstText(system.account().userName(), connection.userName()),
                connection.toolKind().name(), system.access().projectId(), system.access().projectCode(),
                system.access().label(), system.publication().id(), system.revision().revisionNo(),
                exposedName, bounded(tool.description(), 1000), tool.sourceKind().name(), tool.sourceRef(),
                tool.workflowVersionId(), tool.capabilityContractHash(), riskLevel(tool),
                argumentsJson, argumentsHash, McpWriteApprovalStatus.PENDING,
                null, null, null, null, null, null, null, null,
                now.plus(APPROVAL_TTL), now));
        return pendingResult(created, false);
    }

    private McpToolCallResult pendingResult(McpWriteApproval approval, boolean duplicate) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("approvalId", approval.id());
        output.put("approvalStatus", McpWriteApprovalStatus.PENDING.name());
        output.put("message", (duplicate ? "相同的操作已在等待审批，没有重复提交。" : "这个操作需要审批，已提交。")
                + "审批单 #" + approval.id() + "，审批人在 ReachAI「写操作审批」通过后会以你的身份执行。"
                + "稍后可调用 " + APPROVAL_STATUS_TOOL + "（approvalId=" + approval.id() + "）查询结果，不要重复提交。");
        output.put("expiresAt", String.valueOf(approval.expiresAt()));
        return new McpToolCallResult(true, output, null, null, null);
    }

    private McpToolCallResult approvalStatus(EmployeeSession session, Map<String, Object> arguments) {
        AiToolConnection connection = session.connection();
        LocalDateTime now = LocalDateTime.now();
        Object rawId = arguments == null ? null : arguments.get("approvalId");
        if (rawId != null && !String.valueOf(rawId).isBlank()) {
            long id;
            try {
                id = Long.parseLong(String.valueOf(rawId).trim());
            } catch (NumberFormatException invalid) {
                return refusal("MCP_APPROVAL_ID_INVALID", "approvalId 应为审批单号（数字）");
            }
            Optional<McpWriteApproval> approval = approvalRepository.findById(id)
                    .filter(found -> found.tenantId().equals(connection.tenantId())
                            && found.businessUserId().equals(connection.businessUserId()));
            return approval.map(found -> new McpToolCallResult(true, employeeView(found, now), null, null, null))
                    .orElseGet(() -> refusal("MCP_APPROVAL_NOT_FOUND", "没有找到你提交的审批单 #" + id));
        }
        List<Map<String, Object>> recent = approvalRepository
                .findByOwner(connection.tenantId(), connection.businessUserId(), 10)
                .stream().map(approval -> employeeView(approval, now)).toList();
        return new McpToolCallResult(true, Map.of("approvals", recent), null, null, null);
    }

    /** A tool-level failure the AI tool can read and explain to the employee. */
    private static McpToolCallResult refusal(String code, String message) {
        return new McpToolCallResult(false, Map.of("message", message), null, null, code);
    }

    /** What the employee (and their AI tool) may see of one request. */
    public Map<String, Object> employeeView(McpWriteApproval approval, LocalDateTime now) {
        McpWriteApprovalStatus state = approval.visibleState(now);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("approvalId", approval.id());
        view.put("approvalStatus", state.name());
        view.put("tool", approval.toolName());
        view.put("businessSystem", firstText(approval.systemName(), approval.projectCode()));
        view.put("submittedAt", String.valueOf(approval.createdAt()));
        view.put("message", switch (state) {
            case PENDING -> "等待审批";
            case EXECUTING -> "审批已通过，正在执行";
            case SUCCEEDED -> "审批已通过，已执行成功";
            case FAILED -> "审批已通过，但执行失败";
            case REJECTED -> "审批被驳回";
            case EXPIRED -> "超过 24 小时没人处理，已过期；需要的话请重新提交";
        });
        if (approval.decidedAt() != null) {
            view.put("decidedAt", String.valueOf(approval.decidedAt()));
        }
        if (approval.decisionComment() != null && !approval.decisionComment().isBlank()) {
            view.put("comment", approval.decisionComment());
        }
        if (approval.resultJson() != null && !approval.resultJson().isBlank()) {
            view.put("result", parseAny(approval.resultJson()));
        }
        if (approval.errorCode() != null) {
            view.put("errorCode", approval.errorCode());
        }
        return view;
    }

    private McpToolCallResult execute(AiToolConnection connection, SystemSession system, McpToolProjection tool,
                                      Map<String, Object> arguments) {
        McpRuntimeExecutionGateway.ExecutionOutcome outcome;
        try {
            outcome = executionGateway.executeAsEmployee(new McpRuntimeExecutionGateway.EmployeeToolExecutionCommand(
                    tool.sourceKind().name(),
                    tool.sourceRef(),
                    tool.workflowVersionId(),
                    tool.name(),
                    arguments == null ? Map.of() : shapedArguments(tool, arguments),
                    connection.id(),
                    connection.toolKind().name(),
                    system.account().externalUserId(),
                    system.access().projectId(),
                    system.access().projectCode(),
                    connection.tenantId(),
                    system.publication().id(),
                    system.revision().revisionNo(),
                    tool.capabilityContractHash()));
        } catch (RuntimeException boundaryFailure) {
            outcome = new McpRuntimeExecutionGateway.ExecutionOutcome(
                    false, "MCP_RUNTIME_EXECUTION_BOUNDARY_FAILED", Map.of(),
                    null, null, "MCP_RUNTIME_EXECUTION_BOUNDARY_FAILED");
        }
        return new McpToolCallResult(outcome.success(), outcome.output(),
                outcome.runId(), outcome.traceId(),
                outcome.success() ? null
                        : (outcome.errorCode() == null ? "MCP_TOOL_EXECUTION_FAILED" : outcome.errorCode()));
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
                system == null ? null : system.access().projectId(),
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

    private static boolean needsApproval(ExposedTool target) {
        return target.system().access().writeApprovalRequired() && !"READ".equals(riskLevel(target.tool()));
    }

    private static String riskLevel(McpToolProjection tool) {
        return tool.riskLevel() == null || tool.riskLevel().isBlank()
                ? "WRITE" : tool.riskLevel().trim().toUpperCase(Locale.ROOT);
    }

    private String canonicalJson(Map<String, Object> arguments) {
        try {
            return canonicalMapper.writeValueAsString(arguments);
        } catch (Exception invalid) {
            throw new McpDomainException("MCP_PARAMS_INVALID", "tools/call arguments cannot be stored");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception invalid) {
            return "{}";
        }
    }

    /** Execution output as stored on the approval; oversized output keeps a truncation note instead. */
    public String resultJson(Map<String, Object> output) {
        String json = toJson(output);
        return json.length() <= RESULT_JSON_LIMIT ? json
                : toJson(Map.of("truncated", true, "preview", json.substring(0, RESULT_JSON_LIMIT - 200)));
    }

    private Map<String, Object> parseObject(String json) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception invalid) {
            return Map.of();
        }
    }

    private Object parseAny(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception invalid) {
            return json;
        }
    }

    private static String firstText(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
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
            return McpDottedArgumentShape.nest(schema);
        } catch (McpDomainException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new McpDomainException("MCP_PUBLICATION_REVISION_INVALID", "the frozen tool schema is invalid");
        }
    }

    private Map<String, Object> shapedArguments(McpToolProjection tool, Map<String, Object> arguments) {
        try {
            return McpDottedArgumentShape.unflatten(arguments,
                    tool.inputSchemaJson() == null ? null : objectMapper.readTree(tool.inputSchemaJson()));
        } catch (Exception unreadable) {
            return arguments;
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

    /** How an approved request ended; {@code refusal} explains a re-check that stopped it before running. */
    public record ApprovedExecution(boolean success, Map<String, Object> output, String errorCode, String refusal,
                                    String runId, String traceId) {
        static ApprovedExecution refused(String code, String reason) {
            return new ApprovedExecution(false, Map.of(), code, reason, null, null);
        }
    }

    public record EmployeeAuditRequest(EmployeeSession session, SystemSession system, String method,
                                       String toolName, boolean success, Long latencyMs,
                                       String errorCategory, String requestBody, String responseBody,
                                       String errorMessage, String traceId, String runId, String remoteIp) {
    }
}
