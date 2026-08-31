package com.enterprise.ai.control.mcp.application.port;

import com.enterprise.ai.control.mcp.domain.publication.McpPublicationItemKind;
import com.enterprise.ai.control.mcp.domain.publication.McpToolProjection;

/**
 * Resolves one publication item source into the tool projection shape that is
 * frozen into a publication revision at publish time.
 *
 * <p>Implementations own exactly one {@link McpPublicationItemKind} and must
 * never read another service's tables directly; the contract is fetched through
 * the owning service's internal or public API. Resolution failures are reported
 * as {@link McpContractResolutionException} with a stable code.</p>
 *
 * <p>The returned projection carries the source-default tool name, description,
 * schema, and risk level; item-level alias/description overrides are applied by
 * the publication application service after resolution.</p>
 */
public interface McpItemContractResolver {

    /** The single source kind this resolver serves. */
    McpPublicationItemKind supportedKind();

    /**
     * Resolves the frozen contract for one source reference.
     *
     * @param sourceRef CAPABILITY: capability qualified name; WORKFLOW: workflow id
     * @return the source-default tool projection (no item overrides applied)
     * @throws McpContractResolutionException when the source is missing, disabled,
     *         has no ACTIVE version, or exposes no resolvable input schema
     */
    McpToolProjection resolve(String sourceRef);
}
