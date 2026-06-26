package com.enterprise.ai.agent.platform.control.identity;

public record PageCatalogRegisterResult(
        String projectCode,
        String appId,
        String pageKey,
        int actionCount
) {
}
