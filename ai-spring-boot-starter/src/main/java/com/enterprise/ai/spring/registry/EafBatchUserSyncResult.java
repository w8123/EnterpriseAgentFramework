package com.enterprise.ai.spring.registry;

import java.util.List;

public record EafBatchUserSyncResult(List<EafUserSyncResult> items) {
    public EafBatchUserSyncResult {
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
