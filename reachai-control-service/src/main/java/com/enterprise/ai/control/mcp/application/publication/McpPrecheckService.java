package com.enterprise.ai.control.mcp.application.publication;

import com.enterprise.ai.control.mcp.application.CompositeMcpItemContractResolver;
import com.enterprise.ai.control.mcp.application.port.McpPublicationRepository;
import com.enterprise.ai.control.mcp.domain.publication.McpPublication;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItem;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Publish-time gate: every enabled item must resolve, IRREVERSIBLE tools must be
 * explicitly acknowledged, and Workflow items must pin a resolvable version.
 */
@Service
public class McpPrecheckService {

    private final McpPublicationRepository publicationRepository;
    private final CompositeMcpItemContractResolver contractResolver;

    public McpPrecheckService(McpPublicationRepository publicationRepository,
                              CompositeMcpItemContractResolver contractResolver) {
        this.publicationRepository = publicationRepository;
        this.contractResolver = contractResolver;
    }

    public List<McpToolProjection> resolveEnabledItems(McpPublication publication) {
        List<McpToolProjection> resolved = new ArrayList<>();
        for (McpPublicationItem item : publicationRepository.findItems(publication.id())) {
            if (!item.enabled()) {
                continue;
            }
            McpToolProjection source = contractResolver.resolve(item.sourceKind(), item.sourceRef());
            McpToolProjection projected = applyOverrides(source, item);
            if (projected.riskLevel() == null || projected.riskLevel().isBlank()
                    || "UNKNOWN".equalsIgnoreCase(projected.riskLevel())) {
                throw new com.enterprise.ai.control.mcp.domain.McpDomainException(
                        "MCP_TOOL_RISK_UNRESOLVABLE",
                        "riskLevel must be explicit before publishing: " + item.sourceRef());
            }
            resolved.add(projected);
        }
        return resolved;
    }

    public boolean containsIrreversible(List<McpToolProjection> tools) {
        return tools.stream().anyMatch(tool -> "IRREVERSIBLE".equalsIgnoreCase(tool.riskLevel() == null
                ? "" : tool.riskLevel()));
    }

    public boolean requiresIrreversibleAcknowledgement(McpPublication publication) {
        try {
            return containsIrreversible(resolveEnabledItems(publication));
        } catch (RuntimeException failure) {
            // Unresolvable items fail precheck before the acknowledgement question matters.
            return false;
        }
    }

    static McpToolProjection applyOverrides(McpToolProjection source, McpPublicationItem item) {
        String name = item.alias() == null || item.alias().isBlank() ? source.name() : item.alias();
        String description = item.descriptionOverride() == null || item.descriptionOverride().isBlank()
                ? source.description() : item.descriptionOverride();
        String riskLevel = item.riskLevelOverride() == null || item.riskLevelOverride().isBlank()
                ? source.riskLevel() : item.riskLevelOverride();
        return new McpToolProjection(
                name,
                description,
                source.inputSchemaJson(),
                source.sourceKind(),
                source.sourceRef(),
                source.workflowVersionId(),
                riskLevel,
                source.capabilityContractHash());
    }
}
