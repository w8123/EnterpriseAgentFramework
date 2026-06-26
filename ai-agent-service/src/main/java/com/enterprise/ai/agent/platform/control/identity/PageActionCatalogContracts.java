package com.enterprise.ai.agent.platform.control.identity;

import java.util.List;
import java.util.Map;

public final class PageActionCatalogContracts {

    private PageActionCatalogContracts() {
    }

    public record PageCatalogRegisterRequest(
            String pageKey,
            String name,
            String routePattern,
            String origin,
            String pageInstanceId,
            Boolean replaceActions,
            List<PageActionDefinitionRequest> actions,
            Map<String, Object> metadata
    ) {
    }

    public record PageActionDefinitionRequest(
            String actionKey,
            String title,
            String description,
            Boolean confirmRequired,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            Map<String, Object> sampleArgs,
            List<String> allowedAgentIds,
            Map<String, Object> metadata
    ) {
    }
}
