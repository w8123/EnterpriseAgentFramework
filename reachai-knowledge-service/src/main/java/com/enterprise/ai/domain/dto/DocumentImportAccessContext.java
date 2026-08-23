package com.enterprise.ai.domain.dto;

/**
 * Control-attested identity and resource-scope assertion for document imports.
 *
 * <p>The tenant/actor values come from the verified internal HMAC headers. The
 * resource fields are part of the same signed request body and are compared
 * with the current Knowledge-owned resource before any mutation.</p>
 */
public record DocumentImportAccessContext(
        String tenantId,
        String actorId,
        String workspaceId,
        String projectCode,
        String scope
) {

    public DocumentImportAccessContext identityOnly() {
        return new DocumentImportAccessContext(tenantId, actorId, null, null, null);
    }
}
