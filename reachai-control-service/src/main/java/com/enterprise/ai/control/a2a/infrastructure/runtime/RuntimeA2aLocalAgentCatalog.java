package com.enterprise.ai.control.a2a.infrastructure.runtime;

import com.enterprise.ai.control.a2a.application.port.A2aLocalAgentCatalog;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class RuntimeA2aLocalAgentCatalog implements A2aLocalAgentCatalog {

    private final RuntimeProxyClient runtimeProxyClient;
    private final ObjectMapper objectMapper;

    @Override
    public PublishedAgent requirePublished(String agentId, long configVersionId) {
        if (agentId == null || agentId.isBlank() || agentId.trim().length() > 128) {
            throw new A2aDomainException("A2A_LOCAL_AGENT_ID_INVALID",
                    "agentId is required and must not exceed 128 characters");
        }
        String requestedAgentId = agentId.trim();
        try {
            JsonNode agent = body(runtimeProxyClient.getAgent(requestedAgentId), "Agent");
            if (!requestedAgentId.equals(requiredText(agent, "id", "Agent id"))) {
                throw unavailable("Runtime returned a different Agent than requested");
            }
            if (!agent.path("enabled").asBoolean(false)) {
                throw new A2aDomainException("A2A_LOCAL_AGENT_DISABLED",
                        "the local Agent is disabled");
            }
            JsonNode versions = body(runtimeProxyClient.listAgentConfigVersions(requestedAgentId),
                    "Agent configuration versions");
            if (!versions.isArray()) {
                throw unavailable("Runtime returned an invalid Agent configuration catalog");
            }
            JsonNode selected = null;
            for (JsonNode candidate : versions) {
                if (candidate.path("id").asLong(-1) == configVersionId) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) {
                throw new A2aDomainException("A2A_AGENT_CONFIG_NOT_FOUND",
                        "the selected Agent configuration version was not found");
            }
            String status = selected.path("status").asText("").toUpperCase(Locale.ROOT);
            if (!("ACTIVE".equals(status) || "ARCHIVED".equals(status))
                    || selected.path("publishedAt").isMissingNode()
                    || selected.path("publishedAt").isNull()) {
                throw new A2aDomainException("A2A_AGENT_CONFIG_NOT_PUBLISHED",
                        "A2A publication requires an immutable published Agent configuration version");
            }
            String selectedAgentId = selected.path("agentId").asText();
            if (!requestedAgentId.equals(selectedAgentId)) {
                throw unavailable("Runtime returned a configuration for a different Agent");
            }
            return new PublishedAgent(
                    agent.path("id").asText(),
                    nullableLong(agent.get("projectId")),
                    nullableText(agent.get("projectCode")),
                    nullableText(agent.get("keySlug")),
                    requiredText(agent, "name", "Agent name"),
                    nullableText(agent.get("description")),
                    true,
                    configVersionId,
                    selected.path("versionNo").asInt(),
                    status);
        } catch (FeignException.NotFound exception) {
            throw new A2aDomainException("A2A_LOCAL_AGENT_NOT_FOUND", "the local Agent was not found");
        } catch (FeignException exception) {
            throw unavailable("Runtime Agent catalog is unavailable");
        }
    }

    private JsonNode body(ResponseEntity<Object> response, String subject) {
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw unavailable(subject + " is unavailable");
        }
        return objectMapper.valueToTree(response.getBody());
    }

    private String requiredText(JsonNode node, String field, String label) {
        String value = node.path(field).asText();
        if (value == null || value.isBlank()) {
            throw unavailable(label + " is missing from the Runtime catalog");
        }
        return value.trim();
    }

    private String nullableText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Long nullableLong(JsonNode node) {
        return node == null || node.isNull() || !node.canConvertToLong() ? null : node.asLong();
    }

    private A2aDomainException unavailable(String detail) {
        return new A2aDomainException("A2A_RUNTIME_CATALOG_UNAVAILABLE", detail);
    }
}
