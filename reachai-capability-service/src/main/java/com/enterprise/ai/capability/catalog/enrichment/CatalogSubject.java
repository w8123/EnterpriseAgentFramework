package com.enterprise.ai.capability.catalog.enrichment;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;

import java.util.List;
import java.util.Locale;

/** A reference to an owning object, never another capability asset or executable contract. */
public record CatalogSubject(String subjectType, Long subjectId, Long projectId, String subjectKey,
                             String title, String description, String contractHash, boolean accepted,
                             String httpMethod, String endpointPath, List<ToolDefinitionParameter> parameters,
                             List<ScanProjectToolEntity> observations) {
    public static final String PROJECT = "PROJECT";
    public static final String GROUP = "GROUP";
    public static final String BUSINESS_METHOD = "BUSINESS_METHOD";
    public static final String HTTP_API = "HTTP_API";
    public static final String SOURCE_OBSERVATION = "SOURCE_OBSERVATION";

    public CatalogSubject {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        observations = observations == null ? List.of() : List.copyOf(observations);
    }

    public static String type(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of(PROJECT, GROUP, BUSINESS_METHOD, HTTP_API, SOURCE_OBSERVATION).contains(type)) {
            throw new IllegalArgumentException("未知的附属资源主体类型");
        }
        return type;
    }

    public boolean operation() {
        return List.of(BUSINESS_METHOD, HTTP_API, SOURCE_OBSERVATION).contains(subjectType);
    }
}
