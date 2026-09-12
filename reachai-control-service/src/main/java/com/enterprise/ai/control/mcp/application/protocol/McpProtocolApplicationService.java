package com.enterprise.ai.control.mcp.application.protocol;

import com.enterprise.ai.control.governance.ControlToolAclDecisionService;
import com.enterprise.ai.control.mcp.application.port.McpCallAuditRepository;
import com.enterprise.ai.control.mcp.application.port.McpClientRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.application.port.McpRuntimeExecutionGateway;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.client.McpClient;
import com.enterprise.ai.control.mcp.domain.client.McpClientStatus;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationRevision;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationStatus;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Revision-driven MCP protocol core: Bearer authentication, tools/list
 * projection from the frozen revision snapshot, and tools/call with the
 * tool-scope / IRREVERSIBLE / Tool-ACL enforcement chain.
 *
 * <p>The bearer token is only used as a SHA-256 lookup key; it never reaches
 * logs, call logs, or error messages.</p>
 */
@Service
public class McpProtocolApplicationService {

    private static final Logger log = LoggerFactory.getLogger(McpProtocolApplicationService.class);

    private final McpClientRepository clientRepository;
    private final McpPublicationRepository publicationRepository;
    private final McpCallAuditRepository callAuditRepository;
    private final ControlToolAclDecisionService aclDecisionService;
    private final McpRuntimeExecutionGateway executionGateway;
    private final ObjectMapper objectMapper;

    public McpProtocolApplicationService(
            McpClientRepository clientRepository,
            McpPublicationRepository publicationRepository,
            McpCallAuditRepository callAuditRepository,
            ControlToolAclDecisionService aclDecisionService,
            McpRuntimeExecutionGateway executionGateway,
            ObjectMapper objectMapper) {
        this.clientRepository = clientRepository;
        this.publicationRepository = publicationRepository;
        this.callAuditRepository = callAuditRepository;
        this.aclDecisionService = aclDecisionService;
        this.executionGateway = executionGateway;
        this.objectMapper = objectMapper;
    }

    /** Resolves the calling credential to its publication and frozen revision. */
    public McpAuthenticatedSession authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new McpDomainException("MCP_CLIENT_UNAUTHENTICATED",
                    "a Bearer API key is required");
        }
        McpClient client = clientRepository.findByApiKeyHash(sha256Hex(bearerToken))
                .orElseThrow(() -> new McpDomainException("MCP_CLIENT_UNAUTHENTICATED",
                        "the MCP API key is invalid"));
        LocalDateTime now = LocalDateTime.now();
        if (client.state() != McpClientStatus.ACTIVE) {
            throw new McpDomainException("MCP_CLIENT_" + client.state().name(),
                    "the MCP client credential is " + client.state().name().toLowerCase(Locale.ROOT));
        }
        if (!client.enabled()) {
            throw new McpDomainException("MCP_CLIENT_DISABLED",
                    "the MCP client credential is disabled");
        }
        if (client.expired(now)) {
            // EXPIRED is a terminal credential state, not a transient view.
            // Persist it before rejecting so management and subsequent auth
            // observe the same irreversible lifecycle fact.
            clientRepository.save(client.expire(now));
            throw new McpDomainException("MCP_CLIENT_EXPIRED",
                    "the MCP client credential has expired");
        }
        McpPublication publication = publicationRepository.findById(client.publicationId())
                .orElseThrow(() -> new McpDomainException("MCP_PUBLICATION_NOT_FOUND",
                        "the bound publication no longer exists"));
        if (publication.state() != McpPublicationStatus.PUBLISHED) {
            throw new McpDomainException("MCP_PUBLICATION_NOT_PUBLISHED",
                    "the bound publication is " + publication.state().name());
        }
        Long revisionId = publication.currentRevisionId();
        if (revisionId == null) {
            throw new McpDomainException("MCP_PUBLICATION_NO_REVISION",
                    "the bound publication has no current revision");
        }
        McpPublicationRevision revision = publicationRepository
                .findRevision(publication.id(), revisionId)
                .orElseThrow(() -> new McpDomainException("MCP_PUBLICATION_NO_REVISION",
                        "the current revision of the bound publication is missing"));
        clientRepository.touchLastUsed(client.id(), now);
        return new McpAuthenticatedSession(client, publication, revision);
    }

    /** tools/list: frozen revision projection narrowed by the client tool scope. */
    public List<Map<String, Object>> visibleTools(McpAuthenticatedSession session) {
        return session.revision().tools().stream()
                .filter(tool -> visibleTo(session.client(), tool))
                .map(tool -> toolView(tool, objectMapper))
                .toList();
    }

    /** tools/call enforcement chain: visibility → scope → IRREVERSIBLE → Tool ACL → execution. */
    public McpToolCallResult callTool(McpAuthenticatedSession session, String toolName,
                                      Map<String, Object> arguments) {
        if (toolName == null || toolName.isBlank()) {
            throw new McpDomainException("MCP_TOOL_NAME_REQUIRED",
                    "tools/call params.name is required");
        }
        McpToolProjection tool = session.revision().tools().stream()
                .filter(candidate -> candidate.name().equals(toolName.trim()))
                .findFirst()
                .orElseThrow(() -> new McpDomainException("MCP_TOOL_NOT_VISIBLE",
                        "tool is not projected in the current revision: " + toolName));
        McpClient client = session.client();
        if (!visibleTo(client, tool)) {
            if (isIrreversible(tool) && !client.toolScope().contains(tool.name())) {
                throw new McpDomainException("MCP_TOOL_IRREVERSIBLE_DENIED",
                        "IRREVERSIBLE tools require an explicit tool scope grant: " + tool.name());
            }
            throw new McpDomainException("MCP_TOOL_SCOPE_DENIED",
                    "tool is outside the credential tool scope: " + tool.name());
        }
        String aclDecision = aclDecisionService.decide(
                client.roles(), client.projectId(), client.projectCode(), "TOOL", tool.sourceRef());
        if (!ControlToolAclDecisionService.DECISION_ALLOW.equals(aclDecision)) {
            throw new McpDomainException("MCP_TOOL_ACL_DENIED",
                    "Tool ACL " + aclDecision + " for source " + tool.sourceRef());
        }
        McpRuntimeExecutionGateway.ExecutionOutcome outcome;
        try {
            outcome = executionGateway.execute(
                    new McpRuntimeExecutionGateway.ToolExecutionCommand(
                            tool.sourceKind().name(),
                            tool.sourceRef(),
                            tool.workflowVersionId(),
                            tool.name(),
                            arguments == null ? Map.of() : arguments,
                            client.id(),
                            client.name(),
                            client.projectId(),
                            client.projectCode(),
                            client.environment(),
                            client.tenantId(),
                            session.publication().id(),
                            session.revision().revisionNo(), tool.capabilityContractHash()));
        } catch (RuntimeException boundaryFailure) {
            outcome = new McpRuntimeExecutionGateway.ExecutionOutcome(
                    false, "MCP_RUNTIME_EXECUTION_BOUNDARY_FAILED", Map.of(),
                    null, null, "MCP_RUNTIME_EXECUTION_BOUNDARY_FAILED");
        }
        return new McpToolCallResult(
                outcome.success(),
                outcome.output(),
                outcome.runId(),
                outcome.traceId(),
                outcome.success() ? null
                        : firstText(outcome.errorCode(), "MCP_TOOL_EXECUTION_FAILED"));
    }

    /** Append-only audit entry for one protocol exchange. */
    public void audit(McpAuditRequest request) {
        McpClient client = request.session() == null ? null : request.session().client();
        callAuditRepository.append(new McpCallAuditRepository.Entry(
                "OUTBOUND",
                client == null ? null : client.publicationId(),
                client == null ? null : client.id(),
                client == null ? null : bounded(client.name(), null, 128),
                null,
                bounded(request.method(), "unknown", 64),
                bounded(request.toolName(), null, 128),
                client == null ? null : client.projectId(),
                client == null ? null : bounded(client.projectCode(), null, 96),
                client == null ? null : bounded(client.environment(), null, 32),
                client == null ? null : bounded(client.tenantId(), null, 96),
                request.success(),
                request.latencyMs(),
                bounded(request.errorCategory(), null, 32),
                request.requestBody(),
                request.responseBody(),
                bounded(request.errorMessage(), null, 2000),
                bounded(request.traceId(), null, 64),
                bounded(request.runId(), null, 64),
                bounded(request.remoteIp(), null, 64),
                LocalDateTime.now()));
    }

    private static String bounded(String value, String fallback, int maximumLength) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty()) {
            normalized = fallback;
        }
        if (normalized == null || normalized.length() <= maximumLength) {
            return normalized;
        }
        return normalized.substring(0, maximumLength);
    }

    private boolean visibleTo(McpClient client, McpToolProjection tool) {
        if (isIrreversible(tool)) {
            // IRREVERSIBLE stays hidden unless the scope is an explicit opt-in.
            return client.toolScope().contains(tool.name());
        }
        return client.toolScope().isEmpty() || client.toolScope().contains(tool.name());
    }

    private boolean isIrreversible(McpToolProjection tool) {
        return "IRREVERSIBLE".equalsIgnoreCase(tool.riskLevel() == null ? "" : tool.riskLevel());
    }

    private static Map<String, Object> toolView(McpToolProjection tool, ObjectMapper objectMapper) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", tool.name());
        view.put("description", tool.description());
        view.put("inputSchema", parseSchema(objectMapper, tool.inputSchemaJson()));
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("riskLevel", tool.riskLevel() == null ? "WRITE" : tool.riskLevel());
        annotations.put("sourceKind", tool.sourceKind().name());
        view.put("annotations", annotations);
        return view;
    }

    private static Object parseSchema(ObjectMapper objectMapper, String inputSchemaJson) {
        if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
            throw new McpDomainException("MCP_PUBLICATION_REVISION_INVALID",
                    "the frozen tool schema is missing");
        }
        try {
            var schema = objectMapper.readTree(inputSchemaJson);
            if (schema == null || !schema.isObject()) {
                throw new McpDomainException("MCP_PUBLICATION_REVISION_INVALID",
                        "the frozen tool schema must be a JSON object");
            }
            return schema;
        } catch (McpDomainException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            log.warn("MCP tool input schema is not valid JSON in the revision snapshot");
            throw new McpDomainException("MCP_PUBLICATION_REVISION_INVALID",
                    "the frozen tool schema is invalid");
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        }
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    public record McpAuthenticatedSession(McpClient client, McpPublication publication,
                                          McpPublicationRevision revision) {
        public McpAuthenticatedSession {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(publication, "publication");
            Objects.requireNonNull(revision, "revision");
        }
    }

    public record McpToolCallResult(boolean success, Map<String, Object> output,
                                    String runId, String traceId, String errorCode) {
    }

    public record McpAuditRequest(McpAuthenticatedSession session,
                                  String method,
                                  String toolName,
                                  boolean success,
                                  Long latencyMs,
                                  String errorCategory,
                                  String requestBody,
                                  String responseBody,
                                  String errorMessage,
                                  String traceId,
                                  String runId,
                                  String remoteIp) {
    }
}
