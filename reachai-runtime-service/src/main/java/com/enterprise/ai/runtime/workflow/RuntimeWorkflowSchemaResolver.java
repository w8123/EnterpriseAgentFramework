package com.enterprise.ai.runtime.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Resolves the public Workflow input/output schemas without requiring authors to
 * duplicate the schemas already declared by GraphSpec.
 *
 * <p>Published version data is checked before the mutable working copy so an
 * Agent Tool always exposes the contract pinned to its Workflow version.</p>
 */
public final class RuntimeWorkflowSchemaResolver {

    private RuntimeWorkflowSchemaResolver() {
    }

    public static String inputSchemaJson(ObjectMapper objectMapper,
                                         RuntimeWorkflowDefinitionEntity workflow,
                                         RuntimeWorkflowVersionEntity version) {
        return resolve(objectMapper, workflow, version, "inputSchemaJson", "inputSchema");
    }

    public static String outputSchemaJson(ObjectMapper objectMapper,
                                          RuntimeWorkflowDefinitionEntity workflow,
                                          RuntimeWorkflowVersionEntity version) {
        return resolve(objectMapper, workflow, version, "outputSchemaJson", "outputSchema");
    }

    private static String resolve(ObjectMapper objectMapper,
                                  RuntimeWorkflowDefinitionEntity workflow,
                                  RuntimeWorkflowVersionEntity version,
                                  String snapshotField,
                                  String graphField) {
        if (version != null) {
            Map<String, Object> snapshot = readMap(objectMapper, version.getSnapshotJson());
            String explicitPublishedSchema = schemaJson(objectMapper, snapshot.get(snapshotField));
            if (StringUtils.hasText(explicitPublishedSchema)) {
                return explicitPublishedSchema;
            }
            String publishedGraph = firstText(version.getGraphSpecSnapshotJson(), text(snapshot.get("graphSpec")));
            String publishedGraphSchema = graphSchemaJson(objectMapper, publishedGraph, graphField);
            if (StringUtils.hasText(publishedGraphSchema)) {
                return publishedGraphSchema;
            }
            return null;
        }
        if (workflow == null) {
            return null;
        }
        String explicitWorkingSchema = "inputSchemaJson".equals(snapshotField)
                ? workflow.getInputSchemaJson()
                : workflow.getOutputSchemaJson();
        if (StringUtils.hasText(explicitWorkingSchema)) {
            return explicitWorkingSchema.trim();
        }
        return graphSchemaJson(objectMapper, workflow.getGraphSpecJson(), graphField);
    }

    public static String publishedInputSchemaJson(ObjectMapper objectMapper,
                                                 RuntimeWorkflowPublishedVersionView version) {
        if (version == null) return null;
        Map<String, Object> snapshot = readMap(objectMapper, version.getSnapshotJson());
        String explicit = schemaJson(objectMapper, snapshot.get("inputSchemaJson"));
        return StringUtils.hasText(explicit) ? explicit : graphSchemaJson(objectMapper,
                firstText(version.getGraphSpecSnapshotJson(), text(snapshot.get("graphSpec"))), "inputSchema");
    }

    /** An explicit tool binding with properties overrides the pinned published input contract. */
    public static String publishedInputSchemaJson(ObjectMapper objectMapper,
                                                  RuntimeWorkflowPublishedVersionView version,
                                                  String bindingOverrideJson) {
        Map<String, Object> override = readMap(objectMapper, bindingOverrideJson);
        if (override != null && override.get("properties") instanceof Map<?, ?> properties && !properties.isEmpty()) {
            return bindingOverrideJson;
        }
        return publishedInputSchemaJson(objectMapper, version);
    }

    private static String graphSchemaJson(ObjectMapper objectMapper, String graphJson, String graphField) {
        if (!StringUtils.hasText(graphJson)) {
            return null;
        }
        return schemaJson(objectMapper, readMap(objectMapper, graphJson).get(graphField));
    }

    private static Map<String, Object> readMap(ObjectMapper objectMapper, String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String schemaJson(ObjectMapper objectMapper, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String json) {
            return StringUtils.hasText(json) ? json.trim() : null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
