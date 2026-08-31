package com.enterprise.ai.control.mcp.application;

import com.enterprise.ai.control.mcp.application.port.McpContractResolutionException;
import com.enterprise.ai.control.mcp.application.port.McpItemContractResolver;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Routes contract resolution by {@link McpPublicationItemKind} to the single
 * registered per-kind {@link McpItemContractResolver}. This is the entry point
 * the publication application service uses at publish time.
 */
@Component
public class CompositeMcpItemContractResolver {

    private final Map<McpPublicationItemKind, McpItemContractResolver> resolversByKind;

    public CompositeMcpItemContractResolver(List<McpItemContractResolver> resolvers) {
        Map<McpPublicationItemKind, McpItemContractResolver> byKind =
                new EnumMap<>(McpPublicationItemKind.class);
        for (McpItemContractResolver resolver : resolvers) {
            McpItemContractResolver existing = byKind.putIfAbsent(resolver.supportedKind(), resolver);
            if (existing != null) {
                throw new IllegalStateException(
                        "duplicate MCP item contract resolver for kind " + resolver.supportedKind());
            }
        }
        this.resolversByKind = Collections.unmodifiableMap(byKind);
    }

    /**
     * Resolves the contract of one publication item source.
     *
     * @throws McpContractResolutionException when no resolver is registered for
     *         the kind, or the underlying resolution fails
     */
    public McpToolProjection resolve(McpPublicationItemKind sourceKind, String sourceRef) {
        McpItemContractResolver resolver = resolversByKind.get(sourceKind);
        if (resolver == null) {
            throw new McpContractResolutionException("MCP_SOURCE_KIND_UNSUPPORTED",
                    sourceKind, sourceRef,
                    "no contract resolver is registered for source kind " + sourceKind);
        }
        return resolver.resolve(sourceRef);
    }
}
