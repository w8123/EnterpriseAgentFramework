package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.agent.RuntimeAgentRemoteBindingView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContext;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContextResolver;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentSkillBindingSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeResolvedWorkflowTarget;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowPublishedVersionView;
import com.enterprise.ai.runtime.workflow.RuntimePublishedWorkflowSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Builds and persists immutable, server-derived Eval target snapshots. */
@Service
@RequiredArgsConstructor
public class RuntimeEvalTargetSnapshotService {

    public static final int SNAPSHOT_SCHEMA_VERSION = 2;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final RuntimeAgentExecutionContextResolver executionContextResolver;
    private final RuntimeEvalTargetSnapshotMapper snapshotMapper;
    private final ObjectMapper objectMapper;

    public CapturedTarget captureAgent(String agentId,
                                       Long configVersionId,
                                       String tenantId,
                                       String createdBy) {
        Optional<RuntimeAgentExecutionContext> resolved = configVersionId == null
                ? executionContextResolver.resolve(agentId)
                : executionContextResolver.resolveForEvaluation(agentId, configVersionId);
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("Eval target Agent not found: " + agentId);
        }
        RuntimeAgentExecutionContext context = resolved.get();
        if (context.config() == null) {
            throw new IllegalArgumentException("Eval target Agent config not found: " + configVersionId);
        }
        if (!"AGENTSCOPE".equalsIgnoreCase(context.config().getRuntimeType())) {
            throw new IllegalArgumentException("Eval target Agent runtimeType must be AGENTSCOPE");
        }

        String snapshotJson = canonicalSnapshotJson(context);
        String fingerprint = sha256(snapshotJson);
        String normalizedTenantId = firstText(tenantId, "default");
        RuntimeEvalTargetSnapshotEntity existing = snapshotMapper.selectOne(
                Wrappers.<RuntimeEvalTargetSnapshotEntity>lambdaQuery()
                        .eq(RuntimeEvalTargetSnapshotEntity::getTenantId, normalizedTenantId)
                        .eq(RuntimeEvalTargetSnapshotEntity::getTargetType, "AGENT")
                        .eq(RuntimeEvalTargetSnapshotEntity::getTargetId, context.agent().id())
                        .eq(RuntimeEvalTargetSnapshotEntity::getFingerprintSha256, fingerprint)
                        .last("LIMIT 1"));
        if (existing != null) {
            return new CapturedTarget(existing, context);
        }

        RuntimeEvalTargetSnapshotEntity snapshot = new RuntimeEvalTargetSnapshotEntity();
        snapshot.setTenantId(normalizedTenantId);
        snapshot.setProjectCode(context.agent().projectCode());
        snapshot.setTargetType("AGENT");
        snapshot.setTargetId(context.agent().id());
        snapshot.setTargetVersionRef("agent-config:" + context.config().getId());
        snapshot.setAgentConfigVersionId(context.config().getId());
        snapshot.setSourceStatus(normalized(context.config().getStatus()));
        snapshot.setSnapshotSchemaVersion(SNAPSHOT_SCHEMA_VERSION);
        snapshot.setFingerprintSha256(fingerprint);
        snapshot.setSnapshotJson(snapshotJson);
        snapshot.setCreatedBy(normalized(createdBy));
        snapshot.setCreatedAt(LocalDateTime.now());
        try {
            snapshotMapper.insert(snapshot);
        } catch (DuplicateKeyException raced) {
            RuntimeEvalTargetSnapshotEntity winner = snapshotMapper.selectOne(
                    Wrappers.<RuntimeEvalTargetSnapshotEntity>lambdaQuery()
                            .eq(RuntimeEvalTargetSnapshotEntity::getTenantId, normalizedTenantId)
                            .eq(RuntimeEvalTargetSnapshotEntity::getTargetType, "AGENT")
                            .eq(RuntimeEvalTargetSnapshotEntity::getTargetId, context.agent().id())
                            .eq(RuntimeEvalTargetSnapshotEntity::getFingerprintSha256, fingerprint)
                            .last("LIMIT 1"));
            if (winner == null) {
                throw raced;
            }
            snapshot = winner;
        }
        return new CapturedTarget(snapshot, context);
    }

    public CapturedTarget loadAgentSnapshot(Long snapshotId) {
        if (snapshotId == null) {
            throw new IllegalArgumentException("Eval target snapshotId is required");
        }
        RuntimeEvalTargetSnapshotEntity snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null || !"AGENT".equalsIgnoreCase(snapshot.getTargetType())) {
            throw new IllegalArgumentException("Eval Agent target snapshot not found: " + snapshotId);
        }
        if (!StringUtils.hasText(snapshot.getSnapshotJson())
                || !sha256(snapshot.getSnapshotJson()).equalsIgnoreCase(snapshot.getFingerprintSha256())) {
            throw new IllegalStateException("Eval target snapshot fingerprint verification failed: " + snapshotId);
        }
        try {
            Map<String, Object> root = objectMapper.readValue(snapshot.getSnapshotJson(), MAP_TYPE);
            int schemaVersion = intValue(root.get("schemaVersion"), 0);
            if (schemaVersion != SNAPSHOT_SCHEMA_VERSION) {
                throw new IllegalStateException("Unsupported Eval target snapshot schemaVersion: " + schemaVersion);
            }
            RuntimeAgentExecutionContext context = restoreAgentContext(root);
            if (!Objects.equals(snapshot.getTargetId(), context.agent().id())
                    || !Objects.equals(snapshot.getAgentConfigVersionId(), context.config().getId())) {
                throw new IllegalStateException("Eval target snapshot identity does not match its catalog row");
            }
            return new CapturedTarget(snapshot, context);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Eval target snapshot cannot be restored: " + snapshotId, ex);
        }
    }

    String canonicalSnapshotJson(RuntimeAgentExecutionContext context) {
        Map<String, Object> root = ordered(
                "schemaVersion", SNAPSHOT_SCHEMA_VERSION,
                "targetType", "AGENT",
                "agent", agentSnapshot(context.agent()),
                "config", configSnapshot(context.config()),
                "workflowTools", workflowSnapshots(context),
                "skills", skillSnapshots(context.skills()),
                "remoteAgents", remoteAgentSnapshots(context.remoteAgents()));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("Eval target snapshot serialization failed", ex);
        }
    }

    private Map<String, Object> agentSnapshot(RuntimeAgentExecutionView agent) {
        return ordered(
                "id", agent.id(),
                "projectId", agent.projectId(),
                "projectCode", agent.projectCode(),
                "keySlug", agent.keySlug(),
                "name", agent.name(),
                "description", agent.description(),
                "visibility", agent.visibility(),
                "allowedRoles", jsonValue(agent.allowedRolesJson()),
                "enabled", agent.enabled(),
                "activeConfigVersionId", agent.activeConfigVersionId());
    }

    private Map<String, Object> configSnapshot(RuntimeAgentConfigSnapshot config) {
        return ordered(
                "id", config.getId(),
                "agentId", config.getAgentId(),
                "versionNo", config.getVersionNo(),
                "sourceStatus", normalized(config.getStatus()),
                "runtimeType", normalized(config.getRuntimeType()),
                "systemPrompt", config.getSystemPrompt(),
                "modelInstanceId", config.getModelInstanceId(),
                "maxPlanSteps", config.getMaxPlanSteps(),
                "maxWorkflowCalls", config.getMaxWorkflowCalls(),
                "maxReplans", config.getMaxReplans(),
                "totalTimeoutMs", config.getTotalTimeoutMs(),
                "workflowTimeoutMs", config.getWorkflowTimeoutMs(),
                "pageBridgeTimeoutMs", config.getPageBridgeTimeoutMs(),
                "parallelReadOnly", config.getParallelReadOnly(),
                "policyProfile", normalized(config.getPolicyProfile()),
                "toolCatalogMode", normalized(config.getToolCatalogMode()),
                "config", jsonValue(config.getConfigJson()));
    }

    private List<Map<String, Object>> workflowSnapshots(RuntimeAgentExecutionContext context) {
        Map<WorkflowBindingKey, RuntimeResolvedWorkflowTarget> targets = new LinkedHashMap<>();
        for (RuntimeResolvedWorkflowTarget target : safeList(context.resolvedTargets())) {
            targets.put(WorkflowBindingKey.of(target.tool()), target);
        }
        List<RuntimeAgentWorkflowToolSnapshot> tools = new ArrayList<>(safeList(context.tools()));
        tools.sort(Comparator.comparing(
                        (RuntimeAgentWorkflowToolSnapshot value) -> value.getPriority() == null
                                ? Integer.MAX_VALUE : value.getPriority())
                .thenComparing(value -> firstText(value.getToolName(), "")));
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (RuntimeAgentWorkflowToolSnapshot tool : tools) {
            RuntimeResolvedWorkflowTarget target = targets.get(WorkflowBindingKey.of(tool));
            if (target == null) {
                throw new IllegalStateException("Eval target Workflow resolution is missing: " + tool.getWorkflowId());
            }
            RuntimePublishedWorkflowSnapshot.read(target.version());
            snapshots.add(ordered(
                    "binding", workflowBindingSnapshot(tool),
                    "workflow", workflowDefinitionSnapshot(target.workflow()),
                    "version", workflowVersionSnapshot(target.version())));
        }
        return List.copyOf(snapshots);
    }

    private record WorkflowBindingKey(String workflowId, Long versionId, String toolName) {
        static WorkflowBindingKey of(RuntimeAgentWorkflowToolSnapshot tool) {
            return new WorkflowBindingKey(tool.getWorkflowId(), tool.getWorkflowVersionId(), tool.getToolName());
        }
    }

    private Map<String, Object> workflowBindingSnapshot(RuntimeAgentWorkflowToolSnapshot tool) {
        return ordered(
                "workflowId", tool.getWorkflowId(),
                "workflowVersionId", tool.getWorkflowVersionId(),
                "toolName", tool.getToolName(),
                "descriptionOverride", tool.getDescriptionOverride(),
                "inputSchemaOverride", jsonValue(tool.getInputSchemaOverrideJson()),
                "outputSchemaOverride", jsonValue(tool.getOutputSchemaOverrideJson()),
                "riskLevel", normalized(tool.getRiskLevel()),
                "permissionKey", tool.getPermissionKey(),
                "readOnly", tool.getReadOnly(),
                "enabled", tool.getEnabled(),
                "priority", tool.getPriority());
    }

    private Map<String, Object> workflowDefinitionSnapshot(RuntimeWorkflowExecutionView workflow) {
        return ordered(
                "id", workflow.getId(),
                "projectId", workflow.getProjectId(),
                "projectCode", workflow.getProjectCode(),
                "keySlug", workflow.getKeySlug(),
                "name", workflow.getName(),
                "description", workflow.getDescription(),
                "workflowKind", workflow.getWorkflowKind(),
                "executionEngine", workflow.getExecutionEngine(),
                "inputSchema", jsonValue(workflow.getInputSchemaJson()),
                "outputSchema", jsonValue(workflow.getOutputSchemaJson()),
                "defaultModelInstanceId", workflow.getDefaultModelInstanceId(),
                "defaultResourceConfig", jsonValue(workflow.getDefaultResourceConfigJson()),
                "definitionAuthority", workflow.getDefinitionAuthority(),
                "status", normalized(workflow.getStatus()));
    }

    private Map<String, Object> workflowVersionSnapshot(RuntimeWorkflowPublishedVersionView version) {
        return ordered(
                "id", version.getId(),
                "workflowId", version.getWorkflowId(),
                "version", version.getVersion(),
                "status", normalized(version.getStatus()),
                "graphSpec", jsonValue(version.getGraphSpecSnapshotJson()),
                "snapshot", jsonValue(version.getSnapshotJson()));
    }

    private List<Map<String, Object>> skillSnapshots(List<RuntimeAgentSkillBindingSnapshot> values) {
        List<RuntimeAgentSkillBindingSnapshot> skills = new ArrayList<>(safeList(values));
        skills.sort(Comparator.comparing(
                        (RuntimeAgentSkillBindingSnapshot value) -> value.getPriority() == null
                                ? Integer.MAX_VALUE : value.getPriority())
                .thenComparing(value -> firstText(value.getPublisher(), ""))
                .thenComparing(value -> firstText(value.getStandardName(), "")));
        return skills.stream().map(skill -> ordered(
                "skillId", skill.getSkillId(),
                "skillVersionId", skill.getSkillVersionId(),
                "publisher", skill.getPublisher(),
                "standardName", skill.getStandardName(),
                "displayName", skill.getDisplayName(),
                "visibility", skill.getVisibility(),
                "projectCode", skill.getProjectCode(),
                "version", skill.getVersion(),
                "sourceSha256", skill.getSourceSha256(),
                "contentTreeSha256", skill.getContentTreeSha256(),
                "sourceRoot", skill.getSourceRoot(),
                "packageManifest", jsonValue(skill.getPackageManifestJson()),
                "riskReport", jsonValue(skill.getRiskReportJson()),
                "hasScripts", skill.getHasScripts(),
                "activationMode", skill.getActivationMode(),
                "scriptPolicy", skill.getScriptPolicy(),
                "required", skill.getRequired(),
                "enabled", skill.getEnabled(),
                "priority", skill.getPriority())).toList();
    }

    private List<Map<String, Object>> remoteAgentSnapshots(List<RuntimeAgentRemoteBindingView> values) {
        List<RuntimeAgentRemoteBindingView> bindings = new ArrayList<>(safeList(values));
        bindings.sort(Comparator.comparing(
                        (RuntimeAgentRemoteBindingView value) -> value.getPriority() == null
                                ? Integer.MAX_VALUE : value.getPriority())
                .thenComparing(value -> firstText(value.getToolName(), "")));
        return bindings.stream().map(binding -> ordered(
                "principalId", binding.getPrincipalId(),
                "remoteAgentId", binding.getRemoteAgentId(),
                "remoteAgentRevisionId", binding.getRemoteAgentRevisionId(),
                "remoteAgentKey", binding.getRemoteAgentKeySnapshot(),
                "toolName", binding.getToolName(),
                "description", binding.getDescriptionSnapshot(),
                "allowedSkillIds", jsonValue(binding.getAllowedSkillIdsJson()),
                "inputModes", jsonValue(binding.getInputModesJson()),
                "outputModes", jsonValue(binding.getOutputModesJson()),
                "riskLevel", normalized(binding.getRiskLevel()),
                "permissionKey", binding.getPermissionKey(),
                "timeoutMs", binding.getTimeoutMs(),
                "priority", binding.getPriority(),
                "enabled", binding.getEnabled())).toList();
    }

    private Object jsonValue(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return sortNode(objectMapper.readTree(raw));
        } catch (Exception ignored) {
            return raw.trim();
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        throw new IllegalStateException("Eval target snapshot object is missing or invalid");
    }

    private List<?> listValue(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list;
        }
        throw new IllegalStateException("Eval target snapshot list is invalid");
    }

    private String requiredText(Map<String, Object> values, String field) {
        String value = text(values.get(field));
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Eval target snapshot field is required: " + field);
        }
        return value;
    }

    private Long requiredLong(Map<String, Object> values, String field) {
        Long value = longValue(values.get(field));
        if (value == null) {
            throw new IllegalStateException("Eval target snapshot field is required: " + field);
        }
        return value;
    }

    private String requiredJson(Map<String, Object> values, String field) {
        String value = jsonString(values.get(field));
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Eval target snapshot JSON field is required: " + field);
        }
        return value;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Eval target snapshot integer is invalid: " + value, ex);
        }
    }

    private int intValue(Object value, int defaultValue) {
        Integer parsed = integerValue(value);
        return parsed == null ? defaultValue : parsed;
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Eval target snapshot long is invalid: " + value, ex);
        }
    }

    private Boolean booleanValue(Object value) {
        if (value == null || value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value);
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return false;
        }
        throw new IllegalStateException("Eval target snapshot boolean is invalid: " + value);
    }

    private String jsonString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(sortNode(objectMapper.valueToTree(value)));
        } catch (Exception ex) {
            throw new IllegalStateException("Eval target snapshot JSON value is invalid", ex);
        }
    }

    private RuntimeAgentExecutionContext restoreAgentContext(Map<String, Object> root) {
        Map<String, Object> agentMap = mapValue(root.get("agent"));
        Map<String, Object> configMap = mapValue(root.get("config"));
        RuntimeAgentExecutionView agent = new RuntimeAgentExecutionView(
                requiredText(agentMap, "id"),
                longValue(agentMap.get("projectId")),
                text(agentMap.get("projectCode")),
                text(agentMap.get("keySlug")),
                text(agentMap.get("name")),
                text(agentMap.get("description")),
                text(agentMap.get("visibility")),
                jsonString(agentMap.get("allowedRoles")),
                booleanValue(agentMap.get("enabled")),
                longValue(agentMap.get("activeConfigVersionId")),
                null,
                null);
        RuntimeAgentConfigSnapshot config = RuntimeAgentConfigSnapshot.builder()
                .id(requiredLong(configMap, "id"))
                .agentId(requiredText(configMap, "agentId"))
                .versionNo(integerValue(configMap.get("versionNo")))
                .status(requiredText(configMap, "sourceStatus"))
                .runtimeType(requiredText(configMap, "runtimeType"))
                .systemPrompt(text(configMap.get("systemPrompt")))
                .modelInstanceId(text(configMap.get("modelInstanceId")))
                .maxPlanSteps(integerValue(configMap.get("maxPlanSteps")))
                .maxWorkflowCalls(integerValue(configMap.get("maxWorkflowCalls")))
                .maxReplans(integerValue(configMap.get("maxReplans")))
                .totalTimeoutMs(integerValue(configMap.get("totalTimeoutMs")))
                .workflowTimeoutMs(integerValue(configMap.get("workflowTimeoutMs")))
                .pageBridgeTimeoutMs(integerValue(configMap.get("pageBridgeTimeoutMs")))
                .parallelReadOnly(booleanValue(configMap.get("parallelReadOnly")))
                .policyProfile(text(configMap.get("policyProfile")))
                .toolCatalogMode(text(configMap.get("toolCatalogMode")))
                .configJson(jsonString(configMap.get("config")))
                .build();

        List<RuntimeAgentWorkflowToolSnapshot> tools = new ArrayList<>();
        List<RuntimeResolvedWorkflowTarget> targets = new ArrayList<>();
        for (Object raw : listValue(root.get("workflowTools"))) {
            Map<String, Object> item = mapValue(raw);
            Map<String, Object> bindingMap = mapValue(item.get("binding"));
            RuntimeAgentWorkflowToolSnapshot tool = RuntimeAgentWorkflowToolSnapshot.builder()
                    .agentId(agent.id())
                    .agentConfigVersionId(config.getId())
                    .workflowId(requiredText(bindingMap, "workflowId"))
                    .workflowVersionId(requiredLong(bindingMap, "workflowVersionId"))
                    .toolName(requiredText(bindingMap, "toolName"))
                    .descriptionOverride(text(bindingMap.get("descriptionOverride")))
                    .inputSchemaOverrideJson(jsonString(bindingMap.get("inputSchemaOverride")))
                    .outputSchemaOverrideJson(jsonString(bindingMap.get("outputSchemaOverride")))
                    .riskLevel(text(bindingMap.get("riskLevel")))
                    .permissionKey(text(bindingMap.get("permissionKey")))
                    .readOnly(booleanValue(bindingMap.get("readOnly")))
                    .enabled(booleanValue(bindingMap.get("enabled")))
                    .priority(integerValue(bindingMap.get("priority")))
                    .build();

            Map<String, Object> workflowMap = mapValue(item.get("workflow"));
            var workflow = RuntimeWorkflowExecutionView.builder();
            workflow.id(requiredText(workflowMap, "id"));
            workflow.projectId(longValue(workflowMap.get("projectId")));
            workflow.projectCode(text(workflowMap.get("projectCode")));
            workflow.keySlug(text(workflowMap.get("keySlug")));
            workflow.name(text(workflowMap.get("name")));
            workflow.description(text(workflowMap.get("description")));
            workflow.workflowKind(text(workflowMap.get("workflowKind")));
            workflow.executionEngine(text(workflowMap.get("executionEngine")));
            workflow.inputSchemaJson(jsonString(workflowMap.get("inputSchema")));
            workflow.outputSchemaJson(jsonString(workflowMap.get("outputSchema")));
            workflow.defaultModelInstanceId(text(workflowMap.get("defaultModelInstanceId")));
            workflow.defaultResourceConfigJson(jsonString(workflowMap.get("defaultResourceConfig")));
            workflow.definitionAuthority(text(workflowMap.get("definitionAuthority")));
            workflow.status(requiredText(workflowMap, "status"));

            Map<String, Object> versionMap = mapValue(item.get("version"));
            var version = RuntimeWorkflowPublishedVersionView.builder();
            version.id(requiredLong(versionMap, "id"));
            version.workflowId(requiredText(versionMap, "workflowId"));
            version.version(text(versionMap.get("version")));
            version.status(text(versionMap.get("status")));
            version.graphSpecSnapshotJson(requiredJson(versionMap, "graphSpec"));
            version.snapshotJson(requiredJson(versionMap, "snapshot"));

            tools.add(tool);
            targets.add(new RuntimeResolvedWorkflowTarget(tool, workflow.build(), version.build()));
        }

        List<RuntimeAgentSkillBindingSnapshot> skills = new ArrayList<>();
        for (Object raw : listValue(root.get("skills"))) {
            Map<String, Object> value = mapValue(raw);
            RuntimeAgentSkillBindingSnapshot skill = RuntimeAgentSkillBindingSnapshot.builder()
                    .agentId(agent.id())
                    .agentConfigVersionId(config.getId())
                    .skillId(longValue(value.get("skillId")))
                    .skillVersionId(longValue(value.get("skillVersionId")))
                    .publisher(text(value.get("publisher")))
                    .standardName(text(value.get("standardName")))
                    .displayName(text(value.get("displayName")))
                    .visibility(text(value.get("visibility")))
                    .projectCode(text(value.get("projectCode")))
                    .version(text(value.get("version")))
                    .sourceSha256(text(value.get("sourceSha256")))
                    .contentTreeSha256(text(value.get("contentTreeSha256")))
                    .sourceRoot(text(value.get("sourceRoot")))
                    .packageManifestJson(jsonString(value.get("packageManifest")))
                    .riskReportJson(jsonString(value.get("riskReport")))
                    .hasScripts(booleanValue(value.get("hasScripts")))
                    .activationMode(text(value.get("activationMode")))
                    .scriptPolicy(text(value.get("scriptPolicy")))
                    .required(booleanValue(value.get("required")))
                    .enabled(booleanValue(value.get("enabled")))
                    .priority(integerValue(value.get("priority")))
                    .build();
            skills.add(skill);
        }

        List<RuntimeAgentRemoteBindingView> remoteAgents = new ArrayList<>();
        for (Object raw : listValue(root.get("remoteAgents"))) {
            Map<String, Object> value = mapValue(raw);
            var binding = RuntimeAgentRemoteBindingView.builder();
            binding.agentId(agent.id());
            binding.agentConfigVersionId(config.getId());
            binding.principalId(longValue(value.get("principalId")));
            binding.remoteAgentId(longValue(value.get("remoteAgentId")));
            binding.remoteAgentRevisionId(longValue(value.get("remoteAgentRevisionId")));
            binding.remoteAgentKeySnapshot(text(value.get("remoteAgentKey")));
            binding.toolName(text(value.get("toolName")));
            binding.descriptionSnapshot(text(value.get("description")));
            binding.allowedSkillIdsJson(jsonString(value.get("allowedSkillIds")));
            binding.inputModesJson(jsonString(value.get("inputModes")));
            binding.outputModesJson(jsonString(value.get("outputModes")));
            binding.riskLevel(text(value.get("riskLevel")));
            binding.permissionKey(text(value.get("permissionKey")));
            binding.timeoutMs(longValue(value.get("timeoutMs")));
            binding.priority(integerValue(value.get("priority")));
            binding.enabled(booleanValue(value.get("enabled")));
            remoteAgents.add(binding.build());
        }

        return new RuntimeAgentExecutionContext(
                agent, config, List.copyOf(tools), List.copyOf(skills), List.copyOf(remoteAgents),
                List.copyOf(targets), new RuntimeAgentExecutionContext.ResolveTimings(0, 0, 0, 0, 0));
    }

    private JsonNode sortNode(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(item -> array.add(sortNode(item)));
            return array;
        }
        ObjectNode sorted = objectMapper.createObjectNode();
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(fields::add);
        fields.sort(String::compareTo);
        fields.forEach(field -> sorted.set(field, sortNode(node.get(field))));
        return sorted;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private Map<String, Object> ordered(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            result.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return result;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String normalized(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    public record CapturedTarget(RuntimeEvalTargetSnapshotEntity snapshot,
                                 RuntimeAgentExecutionContext executionContext) {
    }
}
