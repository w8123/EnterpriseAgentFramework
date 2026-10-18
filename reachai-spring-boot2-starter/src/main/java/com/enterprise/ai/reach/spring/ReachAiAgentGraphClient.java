package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.auth.ReachAiProjectRequestHeaders;
import com.enterprise.ai.reach.sdk.auth.ReachAiProjectRequestSigner;
import com.enterprise.ai.reach.sdk.graph.ReachAgentGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pushes code-defined {@link ReachAgentGraph} beans to ReachAI as Workflows.
 * Requests carry the request-bound project signature; ReachAI rejects unsigned sync.
 */
public class ReachAiAgentGraphClient {

    private final ReachAiRegistryProperties properties;
    private final ReachAiRegistryTransport transport;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReachAiAgentGraphClient(ReachAiRegistryProperties properties,
                                   ReachAiRegistryTransport transport) {
        this.properties = properties;
        this.transport = transport;
    }

    public String sync(List<ReachAgentGraph> graphs) {
        requireConfigured();
        String projectCode = properties.getProject().getCode().trim();
        if (!projectCode.matches("[a-z0-9][a-z0-9_-]{0,95}")) {
            throw new IllegalStateException("ReachAI project code must be normalized before agent graph sync");
        }
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("syncId", UUID.randomUUID().toString());
        request.put("source", "STARTER");
        request.put("apply", Boolean.TRUE);
        request.put("graphs", graphs == null ? new ArrayList<ReachAgentGraph>() : graphs);
        String path = "/api/registry/projects/" + projectCode + "/agent-graphs/sync";
        try {
            byte[] exactBody = objectMapper.writeValueAsBytes(request);
            ReachAiProjectRequestHeaders signed = ReachAiProjectRequestSigner.sign(
                    properties.getRegistry().getAppKey(),
                    properties.getRegistry().getAppSecret(),
                    projectCode,
                    "POST",
                    path,
                    exactBody);
            return transport.exchange("POST", trimTrailingSlash(properties.getRegistry().getUrl()) + path,
                    signed.toHttpHeaders(), exactBody);
        } catch (Exception failure) {
            throw new IllegalStateException("ReachAI agent graph sync failed", failure);
        }
    }

    private void requireConfigured() {
        if (properties == null
                || !StringUtils.hasText(properties.getRegistry().getUrl())
                || !StringUtils.hasText(properties.getProject().getCode())
                || !StringUtils.hasText(properties.getRegistry().getAppKey())
                || !StringUtils.hasText(properties.getRegistry().getAppSecret())) {
            throw new IllegalStateException(
                    "ReachAI project code, registry URL, app key and app secret are required for agent graph sync");
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
