package com.enterprise.ai.control.a2a.application;

import java.util.List;

public record A2aPageView<T>(
        String schema,
        List<T> items,
        long total,
        int limit,
        int offset) {

    public A2aPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> A2aPageView<T> of(String schema, List<T> items, long total, int limit, int offset) {
        return new A2aPageView<>(schema, items, total, limit, offset);
    }
}
