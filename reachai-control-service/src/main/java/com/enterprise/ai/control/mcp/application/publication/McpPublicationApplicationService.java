package com.enterprise.ai.control.mcp.application.publication;

import com.enterprise.ai.control.mcp.application.port.McpClientRepository;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.client.McpClient;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItem;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationRevision;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * State-machine write entry point for MCP publications. Every mutation goes
 * through the domain aggregate; the frozen revision is produced only by
 * {@link #publish(long, boolean)} and is immutable afterwards.
 */
@Service
public class McpPublicationApplicationService {

    private final McpPublicationRepository publicationRepository;
    private final McpClientRepository clientRepository;
    private final McpPrecheckService precheckService;
    private final com.enterprise.ai.control.mcp.application.CompositeMcpItemContractResolver contractResolver;
    private final ObjectMapper objectMapper;

    public McpPublicationApplicationService(McpPublicationRepository publicationRepository,
                                            McpClientRepository clientRepository,
                                            McpPrecheckService precheckService,
                                            com.enterprise.ai.control.mcp.application.CompositeMcpItemContractResolver contractResolver,
                                            ObjectMapper objectMapper) {
        this.publicationRepository = publicationRepository;
        this.clientRepository = clientRepository;
        this.precheckService = precheckService;
        this.contractResolver = contractResolver;
        this.objectMapper = objectMapper;
    }

    public McpPublicationRepository.Page list(String search, String state, Integer limit, Integer offset) {
        return publicationRepository.findPage(
                trimToNull(search),
                trimToNull(state),
                clamp(limit == null ? 20 : limit, 1, 200),
                Math.max(offset == null ? 0 : offset, 0));
    }

    public McpPublication require(long publicationId) {
        return publicationRepository.findById(publicationId)
                .orElseThrow(() -> new McpDomainException("MCP_PUBLICATION_NOT_FOUND",
                        "publication not found: " + publicationId));
    }

    public DetailView detail(long publicationId) {
        McpPublication publication = require(publicationId);
        return new DetailView(
                view(publication),
                publicationRepository.findItems(publicationId).stream().map(this::itemView).toList(),
                publicationRepository.findRevisions(publicationId).stream().map(this::revisionView).toList(),
                clientRepository.findByPublication(publicationId).stream().map(this::clientView).toList(),
                currentToolNames(publication));
    }

    public McpPublication create(String name, String description) {
        if (publicationRepository.existsByName(name == null ? "" : name.trim())) {
            throw new McpDomainException("MCP_PUBLICATION_NAME_CONFLICT",
                    "publication name is already in use: " + name);
        }
        return publicationRepository.save(McpPublication.draft(name, description));
    }

    public McpPublication rename(long publicationId, String name, String description) {
        McpPublication current = require(publicationId);
        McpPublication renamed = current.rename(
                name == null ? current.name() : name,
                description == null ? current.description() : description);
        return publicationRepository.save(renamed);
    }

    public McpPublicationItem addItem(long publicationId, McpPublicationItemKind sourceKind,
                                      String sourceRef, String alias, String descriptionOverride,
                                      String riskLevelOverride) {
        McpPublication publication = require(publicationId);
        requireDraftLike(publication);
        McpPublicationItem item = new McpPublicationItem(
                null, publicationId, sourceKind, sourceRef, alias, descriptionOverride, riskLevelOverride,
                true, null, null);
        return publicationRepository.saveItem(item);
    }

    public void removeItem(long publicationId, long itemId) {
        McpPublication publication = require(publicationId);
        requireDraftLike(publication);
        if (!publicationRepository.deleteItem(publicationId, itemId)) {
            throw new McpDomainException("MCP_PUBLICATION_ITEM_NOT_FOUND",
                    "publication item not found: " + itemId);
        }
    }

    /** Design-time preview: resolves every enabled item without freezing anything. */
    public List<Map<String, Object>> resolvePreview(long publicationId) {
        McpPublication publication = require(publicationId);
        List<Map<String, Object>> preview = new ArrayList<>();
        for (McpPublicationItem item : publicationRepository.findItems(publicationId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", item.id());
            row.put("sourceKind", item.sourceKind().name());
            row.put("sourceRef", item.sourceRef());
            row.put("enabled", item.enabled());
            if (!item.enabled()) {
                row.put("resolvable", true);
                preview.add(row);
                continue;
            }
            try {
                McpToolProjection resolved = McpPrecheckService.applyOverrides(
                        contractResolve(item), item);
                row.put("resolvable", true);
                row.put("tool", toolView(resolved));
            } catch (RuntimeException failure) {
                row.put("resolvable", false);
                row.put("problem", failure.getMessage() == null ? "resolution failed" : failure.getMessage());
            }
            preview.add(row);
        }
        return preview;
    }

    /** Full publish gate: every enabled item resolves and IRREVERSIBLE is acknowledged. */
    public Map<String, Object> precheck(long publicationId) {
        McpPublication publication = require(publicationId);
        List<McpToolProjection> tools = precheckService.resolveEnabledItems(publication);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", true);
        report.put("toolCount", tools.size());
        report.put("irreversibleToolCount", tools.stream()
                .filter(tool -> "IRREVERSIBLE".equalsIgnoreCase(tool.riskLevel() == null ? "" : tool.riskLevel()))
                .count());
        report.put("riskSummary", riskSummary(tools));
        report.put("tools", tools.stream().map(this::toolView).toList());
        return report;
    }

    /** Freezes the next revision from the current items and repoints the publication. */
    @Transactional
    public DetailView publish(long publicationId, boolean irreversibleAcknowledged) {
        McpPublication publication = require(publicationId);
        List<McpToolProjection> tools = precheckService.resolveEnabledItems(publication);
        if (tools.isEmpty()) {
            throw new McpDomainException("MCP_PUBLICATION_EMPTY",
                    "a publication needs at least one enabled item before publishing");
        }
        if (precheckService.containsIrreversible(tools) && !irreversibleAcknowledged) {
            throw new McpDomainException("MCP_IRREVERSIBLE_ACKNOWLEDGEMENT_REQUIRED",
                    "the revision projects IRREVERSIBLE tools; publish requires an explicit acknowledgement");
        }
        int revisionNo = publicationRepository.nextRevisionNo(publicationId);
        McpPublicationRevision revision = publicationRepository.saveRevision(new McpPublicationRevision(
                null, publicationId, revisionNo, tools, riskSummaryJson(tools), LocalDateTime.now()));
        McpPublication published = publication.validating().ready()
                .publish(revision.id(), LocalDateTime.now());
        return detail(publicationRepository.save(published).id());
    }

    public DetailView suspend(long publicationId) {
        return detail(publicationRepository.save(require(publicationId).suspend(LocalDateTime.now())).id());
    }

    public DetailView resume(long publicationId) {
        return detail(publicationRepository.save(require(publicationId).resume(LocalDateTime.now())).id());
    }

    public DetailView archive(long publicationId) {
        return detail(publicationRepository.save(require(publicationId).archive(LocalDateTime.now())).id());
    }

    /** Rollback only repoints the current revision pointer; snapshots stay immutable. */
    public DetailView rollback(long publicationId, long revisionId) {
        McpPublication publication = require(publicationId);
        McpPublicationRevision revision = publicationRepository
                .findRevision(publicationId, revisionId)
                .orElseThrow(() -> new McpDomainException("MCP_REVISION_NOT_FOUND",
                        "revision not found for publication " + publicationId + ": " + revisionId));
        McpPublication rolledBack = publication.rollbackTo(revision.id(), LocalDateTime.now());
        return detail(publicationRepository.save(rolledBack).id());
    }

    public List<RevisionView> revisions(long publicationId) {
        require(publicationId);
        return publicationRepository.findRevisions(publicationId).stream()
                .map(this::revisionView).toList();
    }

    /**
     * Tool names the publication can currently serve: the frozen revision when
     * one exists, otherwise the design-time resolved item names. Used by the
     * client scope validation to reject tool names outside the publication.
     */
    public Set<String> availableToolNames(long publicationId) {
        McpPublication publication = require(publicationId);
        Long revisionId = publication.currentRevisionId();
        if (revisionId != null) {
            return publicationRepository.findRevision(publicationId, revisionId)
                    .map(revision -> Set.copyOf(revision.tools().stream()
                            .map(McpToolProjection::name).toList()))
                    .orElseThrow(() -> new McpDomainException("MCP_PUBLICATION_REVISION_INVALID",
                            "the current publication revision is missing"));
        }
        return Set.copyOf(precheckService.resolveEnabledItems(publication).stream()
                .map(McpToolProjection::name).toList());
    }

    private List<String> currentToolNames(McpPublication publication) {
        if (publication.currentRevisionId() == null) return List.of();
        return publicationRepository.findRevision(publication.id(), publication.currentRevisionId())
                .map(revision -> revision.tools().stream().map(McpToolProjection::name).sorted().toList())
                .orElseThrow(() -> new McpDomainException("MCP_PUBLICATION_REVISION_INVALID",
                        "the current publication revision is missing"));
    }

    private McpToolProjection contractResolve(McpPublicationItem item) {
        return contractResolver.resolve(item.sourceKind(), item.sourceRef());
    }

    private void requireDraftLike(McpPublication publication) {
        if (publication.state().terminal()) {
            throw new McpDomainException("MCP_PUBLICATION_ARCHIVED",
                    "an archived publication cannot be edited");
        }
    }

    private Map<String, Object> riskSummary(List<McpToolProjection> tools) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String level : List.of("READ", "WRITE", "PAGE_ACTION", "IRREVERSIBLE", "UNKNOWN")) {
            summary.put(level.toLowerCase(Locale.ROOT), 0);
        }
        for (McpToolProjection tool : tools) {
            String level = tool.riskLevel() == null || tool.riskLevel().isBlank()
                    ? "UNKNOWN" : tool.riskLevel().toUpperCase(Locale.ROOT);
            String key = summary.containsKey(level.toLowerCase(Locale.ROOT))
                    ? level.toLowerCase(Locale.ROOT) : "unknown";
            summary.put(key, ((Number) summary.get(key)).intValue() + 1);
        }
        return summary;
    }

    private String riskSummaryJson(List<McpToolProjection> tools) {
        try {
            return objectMapper.writeValueAsString(riskSummary(tools));
        } catch (JsonProcessingException failure) {
            throw new McpDomainException("MCP_REVISION_DATA_INVALID",
                    "risk summary could not be serialized");
        }
    }

    private PublicationView view(McpPublication publication) {
        return new PublicationView(
                publication.id(), publication.name(), publication.description(),
                publication.state().name(), publication.currentRevisionId(),
                publication.createdAt(), publication.updatedAt());
    }

    private ItemView itemView(McpPublicationItem item) {
        return new ItemView(
                item.id(), item.publicationId(), item.sourceKind().name(), item.sourceRef(),
                item.alias(), item.descriptionOverride(), item.riskLevelOverride(), item.enabled(),
                item.createdAt(), item.updatedAt());
    }

    private RevisionView revisionView(McpPublicationRevision revision) {
        return new RevisionView(
                revision.id(), revision.publicationId(), revision.revisionNo(),
                revision.tools().size(), revision.riskSummaryJson(), revision.publishedAt());
    }

    private ClientSummaryView clientView(McpClient client) {
        return new ClientSummaryView(
                client.id(), client.publicationId(), client.name(), client.projectId(), client.projectCode(),
                client.environment(), client.tenantId(), client.apiKeyPrefix(),
                client.roles(), client.toolScope(), client.state().name(), client.enabled(),
                client.expiresAt(), client.lastUsedAt(), client.createdAt());
    }

    private Map<String, Object> toolView(McpToolProjection tool) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", tool.name());
        view.put("description", tool.description());
        view.put("inputSchema", parseSchema(tool.inputSchemaJson()));
        view.put("sourceKind", tool.sourceKind().name());
        view.put("sourceRef", tool.sourceRef());
        if (tool.workflowVersionId() != null) {
            view.put("workflowVersionId", tool.workflowVersionId());
        }
        view.put("riskLevel", tool.riskLevel());
        return view;
    }

    private Object parseSchema(String inputSchemaJson) {
        if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
            throw new McpDomainException("MCP_PUBLICATION_REVISION_INVALID",
                    "the projected tool input schema is missing");
        }
        try {
            var schema = objectMapper.readTree(inputSchemaJson);
            if (schema == null || !schema.isObject()) {
                throw new McpDomainException("MCP_PUBLICATION_REVISION_INVALID",
                        "the projected tool input schema must be a JSON object");
            }
            return schema;
        } catch (McpDomainException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new McpDomainException("MCP_PUBLICATION_REVISION_INVALID",
                    "the projected tool input schema is invalid");
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    public record PublicationView(
            Long id, String name, String description, String state, Long currentRevisionId,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record ItemView(
            Long id, long publicationId, String sourceKind, String sourceRef, String alias,
            String descriptionOverride, String riskLevelOverride, boolean enabled,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record RevisionView(
            Long id, long publicationId, int revisionNo, int toolCount, String riskSummaryJson,
            LocalDateTime publishedAt) {
    }

    public record ClientSummaryView(
            Long id, long publicationId, String name, Long projectId, String projectCode,
            String environment, String tenantId, String apiKeyPrefix, List<String> roles,
            List<String> toolScope, String state, boolean enabled, LocalDateTime expiresAt,
            LocalDateTime lastUsedAt, LocalDateTime createdAt) {
    }

    public record DetailView(
            PublicationView publication,
            List<ItemView> items,
            List<RevisionView> revisions,
            List<ClientSummaryView> clients,
            List<String> availableToolNames) {
    }
}
