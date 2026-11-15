package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.ActionView;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.PageView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** Pins the Control-owned declaration, not the behavior of an arbitrary host callback. */
final class PageActionContractFingerprint {
    private PageActionContractFingerprint() { }

    static String of(ActionView action, PageView page, ObjectMapper mapper) {
        if (action == null || action.id() == null || page == null || page.id() == null
                || !java.util.Objects.equals(action.pageId(), page.id())) return null;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("page.id", page.id());
        fields.put("page.projectId", page.projectId());
        fields.put("page.projectCode", page.projectCode());
        fields.put("page.pageKey", page.pageKey());
        fields.put("page.name", page.name());
        fields.put("page.description", page.description());
        fields.put("page.routePattern", page.routePattern());
        fields.put("page.businessPageUrl", page.businessPageUrl());
        fields.put("page.componentPath", page.componentPath());
        fields.put("page.sourceType", page.sourceType());
        fields.put("page.lifecycleStatus", page.lifecycleStatus());
        fields.put("id", action.id());
        fields.put("pageId", action.pageId());
        fields.put("projectId", action.projectId());
        fields.put("projectCode", action.projectCode());
        fields.put("pageKey", action.pageKey());
        fields.put("actionKey", action.actionKey());
        fields.put("title", action.title());
        fields.put("description", action.description());
        fields.put("actionType", action.actionType());
        fields.put("riskLevel", action.riskLevel());
        fields.put("confirmRequired", action.confirmRequired());
        fields.put("permissionKey", action.permissionKey());
        fields.put("inputSchema", action.inputSchema());
        fields.put("outputSchema", action.outputSchema());
        fields.put("sampleArgs", action.sampleArgs());
        fields.put("allowedAgentIds", action.allowedAgentIds());
        fields.put("implementationRef", action.implementationRef());
        fields.put("sourceType", action.sourceType());
        fields.put("status", action.status());
        fields.put("metadata", action.metadata());
        try {
            byte[] json = mapper.writeValueAsString(canonical(mapper.valueToTree(fields), mapper))
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception invalid) {
            throw new IllegalStateException("Cannot fingerprint page action declaration", invalid);
        }
    }

    private static JsonNode canonical(JsonNode node, ObjectMapper mapper) {
        if (node == null || node.isNull()) return mapper.getNodeFactory().nullNode();
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> sorted.set(name, canonical(node.get(name), mapper)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode values = mapper.createArrayNode();
            node.forEach(value -> values.add(canonical(value, mapper)));
            return values;
        }
        return node;
    }
}
