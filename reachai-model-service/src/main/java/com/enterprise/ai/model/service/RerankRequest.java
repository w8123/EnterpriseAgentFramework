package com.enterprise.ai.model.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RerankRequest {

    private String modelInstanceId;

    private String query;

    private List<String> documents;

    private Integer topN;

    private Map<String, Object> options;
}
