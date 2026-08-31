package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.a2a.RuntimeA2aRemoteAgentBindingEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContext;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContextResolver;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentSkillBindingEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.agent.RuntimeResolvedWorkflowTarget;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
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

    public static final int SNAPSHOT_SCHEMA_VERSION = 1;
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
        RuntimeAgentExecutionContext context = freeze(resolved.get());
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

    private Map<String, Object> configSnapshot(RuntimeAgentConfigVersionEntity config) {
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
        Map<String, RuntimeResolvedWorkflowTarget> targets = new LinkedHashMap<>();
        for (RuntimeResolvedWorkflowTarget target : safeList(context.resolvedTargets())) {
            targets.put(target.tool().getWorkflowId(), target);
        }
        List<RuntimeAgentWorkflowToolEntity> tools = new ArrayList<>(safeList(context.tools()));
        tools.sort(Comparator.comparing(
                        (RuntimeAgentWorkflowToolEntity value) -> value.getPriority() == null
                                ? Integer.MAX_VALUE : value.getPriority())
                .thenComparing(value -> firstText(value.getToolName(), "")));
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (RuntimeAgentWorkflowToolEntity tool : tools) {
            RuntimeResolvedWorkflowTarget target = targets.get(tool.getWorkflowId());
            if (target == null) {
                throw new IllegalStateException("Eval target Workflow resolution is missing: " + tool.getWorkflowId());
            }
            snapshots.add(ordered(
                    "binding", workflowBindingSnapshot(tool),
                    "workflow", workflowDefinitionSnapshot(target.workflow()),
                    "version", workflowVersionSnapshot(target.version())));
        }
        return List.copyOf(snapshots);
    }

    private Map<String, Object> workflowBindingSnapshot(RuntimeAgentWorkflowToolEntity tool) {
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

    private Map<String, Object> workflowDefinitionSnapshot(RuntimeWorkflowDefinitionEntity workflow) {
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

    private Map<String, Object> workflowVersionSnapshot(RuntimeWorkflowVersionEntity version) {
        return ordered(
                "id", version.getId(),
                "workflowId", version.getWorkflowId(),
                "version", version.getVersion(),
                "status", normalized(version.getStatus()),
                "graphSpec", jsonValue(version.getGraphSpecSnapshotJson()));
    }

    private List<Map<String, Object>> skillSnapshots(List<RuntimeAgentSkillBindingEntity> values) {
        List<RuntimeAgentSkillBindingEntity> skills = new ArrayList<>(safeList(values));
        skills.sort(Comparator.comparing(
                        (RuntimeAgentSkillBindingEntity value) -> value.getPriority() == null
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

    private List<Map<String, Object>> remoteAgentSnapshots(List<RuntimeA2aRemoteAgentBindingEntity> values) {
        List<RuntimeA2aRemoteAgentBindingEntity> bindings = new ArrayList<>(safeList(values));
        bindings.sort(Comparator.comparing(
                        (RuntimeA2aRemoteAgentBindingEntity value) -> value.getPriority() == null
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
        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(requiredLong(configMap, "id"));
        config.setAgentId(requiredText(configMap, "agentId"));
        config.setVersionNo(integerValue(configMap.get("versionNo")));
        config.setStatus(requiredText(configMap, "sourceStatus"));
        config.setRuntimeType(requiredText(configMap, "runtimeType"));
        config.setSystemPrompt(text(configMap.get("systemPrompt")));
        config.setModelInstanceId(text(configMap.get("modelInstanceId")));
        config.setMaxPlanSteps(integerValue(configMap.get("maxPlanSteps")));
        config.setMaxWorkflowCalls(integerValue(configMap.get("maxWorkflowCalls")));
        config.setMaxReplans(integerValue(configMap.get("maxReplans")));
        config.setTotalTimeoutMs(integerValue(configMap.get("totalTimeoutMs")));
        config.setWorkflowTimeoutMs(integerValue(configMap.get("workflowTimeoutMs")));
        config.setPageBridgeTimeoutMs(integerValue(configMap.get("pageBridgeTimeoutMs")));
        config.setParallelReadOnly(booleanValue(configMap.get("parallelReadOnly")));
        config.setPolicyProfile(text(configMap.get("policyProfile")));
        config.setToolCatalogMode(text(configMap.get("toolCatalogMode")));
        config.setConfigJson(jsonString(configMap.get("config")));

        List<RuntimeAgentWorkflowToolEntity> tools = new ArrayList<>();
        List<RuntimeResolvedWorkflowTarget> targets = new ArrayList<>();
        for (Object raw : listValue(root.get("workflowTools"))) {
            Map<String, Object> item = mapValue(raw);
            Map<String, Object> bindingMap = mapValue(item.get("binding"));
            RuntimeAgentWorkflowToolEntity tool = new RuntimeAgentWorkflowToolEntity();
            tool.setAgentId(agent.id());
            tool.setAgentConfigVersionId(config.getId());
            tool.setWorkflowId(requiredText(bindingMap, "workflowId"));
            tool.setWorkflowVersionId(requiredLong(bindingMap, "workflowVersionId"));
            tool.setToolName(requiredText(bindingMap, "toolName"));
            tool.setDescriptionOverride(text(bindingMap.get("descriptionOverride")));
            tool.setInputSchemaOverrideJson(jsonString(bindingMap.get("inputSchemaOverride")));
            tool.setOutputSchemaOverrideJson(jsonString(bindingMap.get("outputSchemaOverride")));
            tool.setRiskLevel(text(bindingMap.get("riskLevel")));
            tool.setPermissionKey(text(bindingMap.get("permissionKey")));
            tool.setReadOnly(booleanValue(bindingMap.get("readOnly")));
            tool.setEnabled(booleanValue(bindingMap.get("enabled")));
            tool.setPriority(integerValue(bindingMap.get("priority")));

            Map<String, Object> workflowMap = mapValue(item.get("workflow"));
            RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
            workflow.setId(requiredText(workflowMap, "id"));
            workflow.setProjectId(longValue(workflowMap.get("projectId")));
            workflow.setProjectCode(text(workflowMap.get("projectCode")));
            workflow.setKeySlug(text(workflowMap.get("keySlug")));
            workflow.setName(text(workflowMap.get("name")));
            workflow.setDescription(text(workflowMap.get("description")));
            workflow.setWorkflowKind(text(workflowMap.get("workflowKind")));
            workflow.setExecutionEngine(text(workflowMap.get("executionEngine")));
            workflow.setInputSchemaJson(jsonString(workflowMap.get("inputSchema")));
            workflow.setOutputSchemaJson(jsonString(workflowMap.get("outputSchema")));
            workflow.setDefaultModelInstanceId(text(workflowMap.get("defaultModelInstanceId")));
            workflow.setDefaultResourceConfigJson(jsonString(workflowMap.get("defaultResourceConfig")));
            workflow.setDefinitionAuthority(text(workflowMap.get("definitionAuthority")));
            workflow.setStatus(requiredText(workflowMap, "status"));

            Map<String, Object> versionMap = mapValue(item.get("version"));
            RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
            version.setId(requiredLong(versionMap, "id"));
            version.setWorkflowId(requiredText(versionMap, "workflowId"));
            version.setVersion(text(versionMap.get("version")));
            version.setStatus(text(versionMap.get("status")));
            version.setGraphSpecSnapshotJson(requiredJson(versionMap, "graphSpec"));

            tools.add(tool);
            targets.add(new RuntimeResolvedWorkflowTarget(tool, workflow, version));
        }

        List<RuntimeAgentSkillBindingEntity> skills = new ArrayList<>();
        for (Object raw : listValue(root.get("skills"))) {
            Map<String, Object> value = mapValue(raw);
            RuntimeAgentSkillBindingEntity skill = new RuntimeAgentSkillBindingEntity();
            skill.setAgentId(agent.id());
            skill.setAgentConfigVersionId(config.getId());
            skill.setSkillId(longValue(value.get("skillId")));
            skill.setSkillVersionId(longValue(value.get("skillVersionId")));
            skill.setPublisher(text(value.get("publisher")));
            skill.setStandardName(text(value.get("standardName")));
            skill.setDisplayName(text(value.get("displayName")));
            skill.setVisibility(text(value.get("visibility")));
            skill.setProjectCode(text(value.get("projectCode")));
            skill.setVersion(text(value.get("version")));
            skill.setSourceSha256(text(value.get("sourceSha256")));
            skill.setContentTreeSha256(text(value.get("contentTreeSha256")));
            skill.setSourceRoot(text(value.get("sourceRoot")));
            skill.setPackageManifestJson(jsonString(value.get("packageManifest")));
            skill.setRiskReportJson(jsonString(value.get("riskReport")));
            skill.setHasScripts(booleanValue(value.get("hasScripts")));
            skill.setActivationMode(text(value.get("activationMode")));
            skill.setScriptPolicy(text(value.get("scriptPolicy")));
            skill.setRequired(booleanValue(value.get("required")));
            skill.setEnabled(booleanValue(value.get("enabled")));
            skill.setPriority(integerValue(value.get("priority")));
            skills.add(skill);
        }

        List<RuntimeA2aRemoteAgentBindingEntity> remoteAgents = new ArrayList<>();
        for (Object raw : listValue(root.get("remoteAgents"))) {
            Map<String, Object> value = mapValue(raw);
            RuntimeA2aRemoteAgentBindingEntity binding = new RuntimeA2aRemoteAgentBindingEntity();
            binding.setAgentId(agent.id());
            binding.setAgentConfigVersionId(config.getId());
            binding.setPrincipalId(longValue(value.get("principalId")));
            binding.setRemoteAgentId(longValue(value.get("remoteAgentId")));
            binding.setRemoteAgentRevisionId(longValue(value.get("remoteAgentRevisionId")));
            binding.setRemoteAgentKeySnapshot(text(value.get("remoteAgentKey")));
            binding.setToolName(text(value.get("toolName")));
            binding.setDescriptionSnapshot(text(value.get("description")));
            binding.setAllowedSkillIdsJson(jsonString(value.get("allowedSkillIds")));
            binding.setInputModesJson(jsonString(value.get("inputModes")));
            binding.setOutputModesJson(jsonString(value.get("outputModes")));
            binding.setRiskLevel(text(value.get("riskLevel")));
            binding.setPermissionKey(text(value.get("permissionKey")));
            binding.setTimeoutMs(longValue(value.get("timeoutMs")));
            binding.setPriority(integerValue(value.get("priority")));
            binding.setEnabled(booleanValue(value.get("enabled")));
            remoteAgents.add(binding);
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

    private RuntimeAgentExecutionContext freeze(RuntimeAgentExecutionContext context) {
        return new RuntimeAgentExecutionContext(
                context.agent(),
                context.config(),
                List.copyOf(safeList(context.tools())),
                List.copyOf(safeList(context.skills())),
                List.copyOf(safeList(context.remoteAgents())),
                List.copyOf(safeList(context.resolvedTargets())),
                context.timings());
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
