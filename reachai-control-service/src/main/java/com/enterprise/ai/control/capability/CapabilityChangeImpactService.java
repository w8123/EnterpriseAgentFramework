package com.enterprise.ai.control.capability;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.control.mcp.application.port.McpPublishedReferenceQuery;
import com.enterprise.ai.control.a2a.application.port.A2aPublishedReferenceQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Combines owner-provided evidence without importing another module's persistence model. */
@Service
@RequiredArgsConstructor
public class CapabilityChangeImpactService {
    private final RuntimeProxyClient runtime;
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper mapper;
    private final McpPublishedReferenceQuery mcp;
    private final A2aPublishedReferenceQuery a2a;

    public ResponseEntity<Object> enrich(ResponseEntity<Object> response, String projectCode, String actorId) {
        if (!response.getStatusCode().is2xxSuccessful() || !(response.getBody() instanceof Map<?, ?> raw)) return response;
        Map<String, Object> page = copy(raw);
        List<Map<String, Object>> records = maps(page.get("records"));
        if (records.isEmpty()) return response;
        List<Map<String, Object>> keys = records.stream().map(row -> Map.<String, Object>of(
                "qualifiedName", Objects.toString(row.get("qualifiedName"), ""),
                "storageName", Objects.toString(row.get("storageName"), ""))).distinct().toList();
        var evidence = inspectReferences(keys, projectCode, actorId);
        for (var record : records) {
            Map<String, Object> impact;
            try { impact = mapper.readValue(String.valueOf(record.get("impactJson")), new com.fasterxml.jackson.core.type.TypeReference<>() { }); }
            catch (Exception invalid) { impact = new LinkedHashMap<>(); }
            if (impact == null) impact = new LinkedHashMap<>();
            impact.putAll(evidence.get(String.valueOf(record.get("qualifiedName"))));
            try { record.put("impactJson", mapper.writeValueAsString(impact)); }
            catch (Exception invalid) { throw new IllegalStateException("能力影响无法序列化", invalid); }
        }
        page.put("records", records);
        return ResponseEntity.status(response.getStatusCode()).body(page);
    }

    public Map<String, Object> references(String projectCode, String qualifiedName, String storageName, String actorId) {
        return inspectReferences(List.of(Map.of("qualifiedName", qualifiedName, "storageName", storageName)),
                projectCode, actorId).get(qualifiedName);
    }

    private Map<String, Map<String, Object>> inspectReferences(List<Map<String, Object>> keys, String projectCode, String actorId) {
        var publishedMcp = new McpPublishedReferenceQuery.Evidence(false, List.of());
        var publishedA2a = new A2aPublishedReferenceQuery.Evidence(false, List.of());
        String publicationState = "COMPLETE";
        try {
            publishedMcp = mcp.inspect();
            if (!publishedMcp.complete()) publicationState = "PARTIAL";
        } catch (Exception unavailable) { publicationState = "UNKNOWN"; }
        try {
            publishedA2a = a2a.inspect();
            if (!publishedA2a.complete() && !"UNKNOWN".equals(publicationState)) publicationState = "PARTIAL";
        } catch (Exception unavailable) { publicationState = "UNKNOWN"; }

        Map<String, Object> runtimeEvidence;
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of("projectCode", projectCode, "capabilities", keys,
                    "agentConfigVersionIds", publishedA2a.bindings().stream()
                            .map(A2aPublishedReferenceQuery.Binding::agentConfigVersionId).distinct().toList()));
            runtimeEvidence = runtime.capabilityReferences(signer.sign("POST", "/internal/runtime/capability-references",
                    InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, actorId, body), body);
            if (runtimeEvidence == null || !(runtimeEvidence.get("usages") instanceof List<?>)
                    || !List.of("COMPLETE", "PARTIAL").contains(runtimeEvidence.get("state"))
                    || ((List<?>) runtimeEvidence.get("usages")).stream().anyMatch(value -> !(value instanceof Map<?, ?> usage)
                        || usage.get("qualifiedName") == null || usage.get("kind") == null || usage.get("id") == null)) {
                throw new IllegalStateException("引用证据响应无效");
            }
        } catch (Exception unavailable) {
            runtimeEvidence = Map.of("state", "UNKNOWN", "usages", List.of());
        }

        var byCapability = maps(runtimeEvidence.get("usages")).stream()
                .collect(Collectors.groupingBy(usage -> String.valueOf(usage.get("qualifiedName"))));
        var directMcp = publishedMcp.bindings().stream().filter(binding -> "CAPABILITY".equals(binding.sourceKind()))
                .collect(Collectors.groupingBy(McpPublishedReferenceQuery.Binding::sourceRef));
        var workflowMcp = publishedMcp.bindings().stream().filter(binding -> "WORKFLOW".equals(binding.sourceKind()))
                .collect(Collectors.groupingBy(binding -> String.valueOf(binding.workflowVersionId())));
        var pinnedA2a = publishedA2a.bindings().stream().collect(Collectors.groupingBy(binding ->
                binding.agentId() + "#" + binding.agentConfigVersionId()));
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> record : keys) {
            String name = String.valueOf(record.get("qualifiedName"));
            List<Map<String, Object>> usages = byCapability.getOrDefault(name, List.of());
            List<Map<String, Object>> references = new ArrayList<>(usages);
            for (String alias : List.of(name, Objects.toString(record.get("storageName"), ""))) {
                directMcp.getOrDefault(alias, List.of()).forEach(binding -> references.add(mcpReference(binding)));
            }
            for (var usage : usages) {
                if ("WORKFLOW".equals(usage.get("kind"))) {
                    workflowMcp.getOrDefault(String.valueOf(usage.get("versionId")), List.of())
                            .forEach(binding -> references.add(mcpReference(binding)));
                }
                if ("AGENT".equals(usage.get("kind"))) {
                    pinnedA2a.getOrDefault(usage.get("id") + "#" + usage.get("agentConfigVersionId"), List.of())
                            .forEach(binding -> references.add(Map.of("kind", "A2A", "id", binding.publicationId(),
                                    "name", Objects.toString(binding.name(), String.valueOf(binding.publicationId())), "stage", "PUBLISHED")));
                }
            }
            Map<String, Object> impact = new LinkedHashMap<>();
            impact.put("runtimeEvidence", runtimeEvidence.get("state"));
            impact.put("publicationEvidence", publicationState);
            impact.put("references", references.stream().distinct().toList());
            impact.put("checkedAt", Instant.now().toString());
            result.put(name, impact);
        }
        return result;
    }

    private static Map<String, Object> mcpReference(McpPublishedReferenceQuery.Binding binding) {
        Map<String, Object> reference = new LinkedHashMap<>(Map.of("kind", "MCP", "id", binding.publicationId(),
                "name", Objects.toString(binding.publicationName(), String.valueOf(binding.publicationId())),
                "stage", "PUBLISHED", "version", "修订 " + binding.revisionNo()));
        if ("WORKFLOW".equals(binding.sourceKind())) {
            reference.put("workflowId", binding.sourceRef());
            reference.put("versionId", binding.workflowVersionId());
        }
        return reference;
    }

    private static Map<String, Object> copy(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>(); map.forEach((key, value) -> result.put(String.valueOf(key), value)); return result;
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> copy((Map<?, ?>) item)).toList();
    }
}
