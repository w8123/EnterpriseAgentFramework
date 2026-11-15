package com.enterprise.ai.domain.dto;

import com.enterprise.ai.external.ExternalKnowledgeConnection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** What the console may see of an external connection: never the API key itself. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExternalKnowledgeView {
    private String provider;
    private String providerName;
    private String baseUrl;
    private String clientId;
    private List<ExternalKnowledgeConnection.RemoteDataset> datasets;
    private boolean apiKeyConfigured;
}
