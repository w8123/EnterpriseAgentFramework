package com.enterprise.ai.control.a2a.application.port;

public interface A2aLocalAgentCatalog {

    PublishedAgent requirePublished(String agentId, long configVersionId);

    record PublishedAgent(
            String agentId,
            Long projectId,
            String projectCode,
            String keySlug,
            String name,
            String description,
            boolean enabled,
            long configVersionId,
            int configVersionNo,
            String configStatus) {
    }
}
