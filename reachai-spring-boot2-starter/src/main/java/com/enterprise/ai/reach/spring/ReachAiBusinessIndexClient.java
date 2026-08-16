package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.auth.ReachAiProjectRequestHeaders;
import com.enterprise.ai.reach.sdk.auth.ReachAiProjectRequestSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Project-scoped structured business-index sync client. Requests are bound to
 * method, canonical path and exact JSON bytes. Attachment upload is deliberately
 * not exposed until it has its own streaming detached-signature contract.
 */
public class ReachAiBusinessIndexClient {

    private final ReachAiRegistryProperties properties;
    private final ReachAiRegistryTransport transport;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;

    public ReachAiBusinessIndexClient(ReachAiRegistryProperties properties,
                                      ReachAiRegistryTransport transport) {
        this.properties = properties;
        this.transport = transport;
        this.baseUrl = trimTrailingSlash(properties == null ? null : properties.getRegistry().getUrl());
    }

    public String upsert(String indexCode, Object businessRecord) {
        return sendJson("POST", path(indexCode) + "/upsert", businessRecord);
    }

    public String batchUpsert(String indexCode, List<?> businessRecords) {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("items", businessRecords);
        return sendJson("POST", path(indexCode) + "/batch", request);
    }

    public String deleteRecord(String indexCode, String bizId) {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("bizId", bizId);
        return sendJson("POST", path(indexCode) + "/delete", request);
    }

    private String sendJson(String method, String path, Object body) {
        if (body == null) {
            throw new IllegalArgumentException("business-index request body is required");
        }
        try {
            return exchange(method, path, objectMapper.writeValueAsBytes(body));
        } catch (Exception failure) {
            if (failure instanceof IllegalStateException) {
                throw (IllegalStateException) failure;
            }
            throw new IllegalStateException("ReachAI business-index JSON serialization failed", failure);
        }
    }

    private String exchange(String method, String path, byte[] exactBody) {
        requireConfigured();
        try {
            ReachAiProjectRequestHeaders signed = ReachAiProjectRequestSigner.sign(
                    properties.getRegistry().getAppKey(),
                    properties.getRegistry().getAppSecret(),
                    properties.getProject().getCode(),
                    method,
                    path,
                    exactBody);
            return transport.exchange(method, baseUrl + path, signed.toHttpHeaders(), exactBody);
        } catch (Exception failure) {
            throw new IllegalStateException("ReachAI business-index request failed", failure);
        }
    }

    private String path(String indexCode) {
        requireConfigured();
        String projectCode = properties.getProject().getCode().trim();
        if (!projectCode.matches("[a-z0-9][a-z0-9_-]{0,95}")) {
            throw new IllegalStateException("ReachAI project code must be normalized before business-index sync");
        }
        if (!StringUtils.hasText(indexCode)
                || !indexCode.trim().matches("[A-Za-z][A-Za-z0-9_]{1,62}")) {
            throw new IllegalArgumentException("business-index indexCode is invalid");
        }
        return "/api/knowledge-ingress/projects/" + segment(projectCode)
                + "/biz-index/" + segment(indexCode);
    }

    private void requireConfigured() {
        if (properties == null || !StringUtils.hasText(baseUrl)
                || !StringUtils.hasText(properties.getProject().getCode())
                || !StringUtils.hasText(properties.getRegistry().getAppKey())
                || !StringUtils.hasText(properties.getRegistry().getAppSecret())) {
            throw new IllegalStateException(
                    "ReachAI project code, registry URL, app key and app secret are required for business-index sync");
        }
    }

    private static String segment(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("path segment is required");
        }
        try {
            return URLEncoder.encode(value.trim(), "UTF-8").replace("+", "%20");
        } catch (Exception impossible) {
            throw new IllegalArgumentException("path segment encoding failed", impossible);
        }
    }

    private static String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
