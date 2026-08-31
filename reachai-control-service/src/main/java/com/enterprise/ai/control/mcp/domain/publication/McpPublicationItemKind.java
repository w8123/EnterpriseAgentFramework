package com.enterprise.ai.control.mcp.domain.publication;

import com.enterprise.ai.control.mcp.domain.McpDomainText;

/** Source of a publication item: a Capability from the catalog or a published Workflow. */
public enum McpPublicationItemKind {
    CAPABILITY,
    WORKFLOW;

    public static McpPublicationItemKind parse(String value) {
        return McpDomainText.parseEnum(McpPublicationItemKind.class, value, "sourceKind");
    }
}
