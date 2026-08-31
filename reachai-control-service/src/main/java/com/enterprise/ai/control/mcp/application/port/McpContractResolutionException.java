package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.mcp.domain.McpDomainException;
import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;

/**
 * Raised when a publication item source cannot be resolved into a freezable
 * {@link com.enterprise.ai.control.mcp.domain.publication.McpToolProjection}.
 * Carries the source kind and reference so publish-time validation can point
 * the operator at the exact failing item.
 */
public final class McpContractResolutionException extends McpDomainException {

    private final McpPublicationItemKind sourceKind;
    private final String sourceRef;

    public McpContractResolutionException(String code,
                                          McpPublicationItemKind sourceKind,
                                          String sourceRef,
                                          String message) {
        super(code, message);
        this.sourceKind = sourceKind;
        this.sourceRef = sourceRef == null ? "" : sourceRef.trim();
    }

    public McpPublicationItemKind sourceKind() {
        return sourceKind;
    }

    public String sourceRef() {
        return sourceRef;
    }
}
