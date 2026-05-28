package com.enterprise.ai.agent.identity;

import java.util.List;

public record BusinessUserBatchSyncResult(List<BusinessUserSyncResult> items) {
    public BusinessUserBatchSyncResult {
        items = items == null ? List.of() : items;
    }

    public int total() {
        return items.size();
    }

    public int success() {
        return (int) items.stream()
                .filter(item -> item.message() == null || item.message().isBlank())
                .count();
    }
}
