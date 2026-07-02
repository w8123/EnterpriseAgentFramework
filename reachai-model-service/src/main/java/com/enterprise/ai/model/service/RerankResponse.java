package com.enterprise.ai.model.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RerankResponse {

    private String model;

    private String provider;

    private List<RerankResult> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RerankResult {
        private int index;
        private float score;
        private String document;
    }
}
