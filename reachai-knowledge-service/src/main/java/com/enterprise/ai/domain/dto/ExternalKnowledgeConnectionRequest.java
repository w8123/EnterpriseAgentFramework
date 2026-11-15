package com.enterprise.ai.domain.dto;

import lombok.Data;

import java.util.List;

/**
 * Console draft of an external knowledge base connection. A blank {@code apiKey} keeps the saved key of
 * {@code kbCode}. {@code scope}/{@code workspaceId}/{@code projectCode} are read by Control's permission check only.
 */
@Data
public class ExternalKnowledgeConnectionRequest {
    private String provider;
    private String baseUrl;
    private String clientId;
    private String apiKey;
    private List<Dataset> datasets;
    private String kbCode;
    private String scope;
    private String workspaceId;
    private String projectCode;

    @Data
    public static class Dataset {
        private String id;
        private String name;
    }
}
