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
public class EmbeddingResponse {

    private String model;

    private String provider;

    private int dimension;

    private List<List<Float>> embeddings;
}
