package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.managed.ManagedArtifactReadService;
import com.enterprise.ai.runtime.managed.ManagedExecutionException;
import com.enterprise.ai.runtime.managed.ManagedExecutionService;
import com.enterprise.ai.runtime.managed.ManagedExecutionStatus;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ArtifactView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.CreateRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ExecutionView;
import com.enterprise.ai.runtime.managed.ManagedExecutorProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Narrow AgentScope bridge for Runtime-owned Managed Executions.
 *
 * <p>Model arguments can describe only an objective or reference an execution id. Workspace,
 * project, user, profile, model, acceptance commands and budgets are resolved from trusted
 * Runtime state and the immutable published Agent configuration version.</p>
 */
@Service
public class ManagedExecutorAgentDelegationService {

    public static final String START_TOOL = "managed_executor.start";
    public static final String STATUS_TOOL = "managed_executor.status";
    public static final String READ_RESULT_TOOL = "managed_executor.read_result";
    public static final Set<String> TOOL_NAMES = Set.of(START_TOOL, STATUS_TOOL, READ_RESULT_TOOL);

    private static final String POLICY_KEY = "managedExecutor";
    private static final String SOURCE_TYPE = "AGENT_DELEGATION";
    private static final String CARD_SCHEMA = "reachai.managed-executor.delegation-card.v1";
    private static final String RESULT_SCHEMA = "reachai.managed-executor.result-reference.v1";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Set<String> POLICY_FIELDS = Set.of(
            "enabled", "autoRouteEnabled", "allowedTools", "sandboxProfile", "modelRef",
            "acceptanceProfile", "priority", "maxWallTimeSeconds", "approvalTimeoutSeconds",
            "maxDelegationsPerRun");
    private static final Pattern PRODUCTION_MUTATION_INTENT = Pattern.compile(
            "(?i)(?:部署(?:到)?生产|发布(?:到)?生产|上线(?:到)?生产|推送(?:到)?生产|"
                    + "部署(?:到)?线上|发布(?:到)?线上|上线(?:到)?线上|推送(?:到)?线上|"
                    + "删除生产数据|清空生产|清库|生产库.{0,20}(?:执行|删除|更新)|线上.{0,20}(?:执行|删除|更新)|"
                    + "\\b(?:deploy|release|publish|push)\\b.{0,40}\\b(?:to\\s+)?(?:production|prod)\\b|"
                    + "\\b(?:delete|drop|truncate|update)\\b.{0,40}\\b(?:production|prod)\\b)");

    private final ManagedExecutionService executionService;
    private final ManagedArtifactReadService artifactReadService;
    private final ManagedExecutorProperties properties;
    private final ObjectMapper objectMapper;

    public ManagedExecutorAgentDelegationService(
            ManagedExecutionService executionService,
            ManagedArtifactReadService artifactReadService,
            ManagedExecutorProperties properties,
            ObjectMapper objectMapper) {
        this.executionService = executionService;
        this.artifactReadService = artifactReadService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Resolve a fail-closed policy for this exact published configuration and trusted identity. */
    public DelegationPolicy resolvePolicy(
            RuntimeAgentConfigVersionEntity config,
            WorkflowExecutionIdentity identity) {
        if (!properties.enabled()
                || config == null
                || config.getId() == null
                || !"PUBLISHED".equalsIgnoreCase(config.getStatus())
                || !trustedInteractiveIdentity(identity)
                || !properties.allowsProject(identity.projectCode())) {
            return DelegationPolicy.disabled();
        }
        return parsePolicy(config, false);
    }

    /**
     * Resolve an Eval-only policy from a captured config snapshot. This path may exercise a
     * version-level automatic-routing choice before the global production switch is enabled, but
     * it never authorizes a real execution.
     */
    public DelegationPolicy resolveEvaluationPolicy(
            RuntimeAgentConfigVersionEntity config,
            String projectCode) {
        if (config == null
                || config.getId() == null
                || !("PUBLISHED".equalsIgnoreCase(config.getStatus())
                    || "DRAFT".equalsIgnoreCase(config.getStatus()))
                || !properties.allowsProject(projectCode)) {
            return DelegationPolicy.disabled();
        }
        return parsePolicy(config, true);
    }

    private DelegationPolicy parsePolicy(RuntimeAgentConfigVersionEntity config, boolean evaluation) {
        Map<String, Object> policy;
        try {
            if (!StringUtils.hasText(config.getConfigJson())) return DelegationPolicy.disabled();
            Map<String, Object> root = objectMapper.readValue(config.getConfigJson(), MAP_TYPE);
            Object rawPolicy = root.get(POLICY_KEY);
            if (!(rawPolicy instanceof Map<?, ?> rawMap)) return DelegationPolicy.disabled();
            policy = objectMapper.convertValue(rawMap, MAP_TYPE);
        } catch (Exception invalid) {
            return DelegationPolicy.disabled();
        }
        if (!POLICY_FIELDS.containsAll(policy.keySet()) || !Boolean.TRUE.equals(policy.get("enabled"))) {
            return DelegationPolicy.disabled();
        }
        try {
            Set<String> allowedTools = strictToolAllowlist(policy.get("allowedTools"));
            String profile = requiredEnum(policy.get("sandboxProfile"),
                    Set.of("ANALYZE_READONLY", "WORKSPACE_PATCH"), "sandboxProfile");
            String acceptanceProfile = requiredIdentifier(
                    policy.get("acceptanceProfile"), "acceptanceProfile", 128);
            int maxWallTimeSeconds = requiredInteger(
                    policy.get("maxWallTimeSeconds"), 60, 14_400, "maxWallTimeSeconds");
            int approvalTimeoutSeconds = requiredInteger(
                    policy.get("approvalTimeoutSeconds"), 30, 1_800, "approvalTimeoutSeconds");
            int priority = optionalInteger(policy.get("priority"), 0, -100, 100, "priority");
            int maxDelegationsPerRun = optionalInteger(
                    policy.get("maxDelegationsPerRun"), 1, 1, 1, "maxDelegationsPerRun");
            String modelRef = optionalIdentifier(policy.get("modelRef"), "modelRef", 128);
            if (!properties.allowsProfile(profile)) return DelegationPolicy.disabled();
            boolean autoRouteEnabled = (evaluation || properties.autoRouteEnabled())
                    && Boolean.TRUE.equals(policy.get("autoRouteEnabled"));
            return new DelegationPolicy(
                    true,
                    autoRouteEnabled,
                    allowedTools,
                    profile,
                    modelRef,
                    acceptanceProfile,
                    priority,
                    maxWallTimeSeconds,
                    approvalTimeoutSeconds,
                    maxDelegationsPerRun);
        } catch (IllegalArgumentException invalid) {
            return DelegationPolicy.disabled();
        }
    }

    /**
     * Returns the exact tools visible to the model for this run. With automatic routing disabled,
     * start is absent unless the original caller explicitly selected Managed Executor mode.
     */
    public Set<String> availableTools(DelegationPolicy policy, Map<String, Object> trustedRequestInput) {
        if (policy == null || !policy.enabled()) return Set.of();
        LinkedHashSet<String> available = new LinkedHashSet<>(policy.allowedTools());
        if ((!policy.autoRouteEnabled() && !explicitStartRequested(trustedRequestInput))
                || productionMutationRequested(trustedRequestInput)) {
            available.remove(START_TOOL);
        }
        return Set.copyOf(available);
    }

    public Map<String, Object> invoke(
            String toolName,
            DelegationPolicy policy,
            RuntimeAgentConfigVersionEntity config,
            WorkflowExecutionIdentity identity,
            Map<String, Object> trustedRequestInput,
            Map<String, Object> modelArgs,
            String traceId) {
        requireTool(policy, toolName);
        requireTrustedIdentity(identity);
        return switch (toolName) {
            case START_TOOL -> start(policy, config, identity, trustedRequestInput, modelArgs, traceId);
            case STATUS_TOOL -> status(policy, config, identity, modelArgs);
            case READ_RESULT_TOOL -> readResult(policy, config, identity, modelArgs);
            default -> throw denied("MANAGED_EXECUTOR_TOOL_NOT_ALLOWED");
        };
    }

    /** Eval shadow call. It records routing behavior without DB rows, Jobs, Artifacts or approvals. */
    public Map<String, Object> simulate(
            String toolName,
            DelegationPolicy policy,
            RuntimeAgentConfigVersionEntity config,
            String projectCode,
            Map<String, Object> trustedRequestInput,
            Map<String, Object> modelArgs,
            String traceId) {
        requireTool(policy, toolName);
        if (!START_TOOL.equals(toolName)) {
            throw denied("MANAGED_EXECUTOR_EVAL_REFERENCE_UNAVAILABLE");
        }
        if (!policy.autoRouteEnabled() && !explicitStartRequested(trustedRequestInput)) {
            throw denied("MANAGED_EXECUTOR_EXPLICIT_REQUEST_REQUIRED");
        }
        if (productionMutationRequested(trustedRequestInput)) {
            throw denied("MANAGED_EXECUTOR_PRODUCTION_MUTATION_REQUIRES_WORKFLOW");
        }
        requireExactArguments(modelArgs, Set.of("objective"));
        String objective = requiredContent(modelArgs.get("objective"), properties.maxObjectiveCharacters());
        String normalizedProject = requiredIdentifier(projectCode, "projectCode", 128)
                .toUpperCase(Locale.ROOT);
        String executionId = "mex_eval_" + sha256(config.getId() + ":" + traceId).substring(0, 24);
        return Map.ofEntries(
                Map.entry("schema", CARD_SCHEMA),
                Map.entry("kind", "MANAGED_EXECUTION"),
                Map.entry("executionId", executionId),
                Map.entry("status", "QUEUED"),
                Map.entry("projectCode", normalizedProject),
                Map.entry("sandboxProfile", policy.sandboxProfile()),
                Map.entry("acceptanceProfile", policy.acceptanceProfile()),
                Map.entry("objectiveSha256", sha256(objective)),
                Map.entry("async", true),
                Map.entry("simulated", true),
                Map.entry("terminal", false),
                Map.entry("resultAvailable", false),
                Map.entry("nextAction", "RETURN_ASYNC_TASK_CARD"),
                Map.entry("productionMutationApplied", false));
    }

    private Map<String, Object> start(
            DelegationPolicy policy,
            RuntimeAgentConfigVersionEntity config,
            WorkflowExecutionIdentity identity,
            Map<String, Object> trustedRequestInput,
            Map<String, Object> modelArgs,
            String traceId) {
        if (!policy.autoRouteEnabled() && !explicitStartRequested(trustedRequestInput)) {
            throw denied("MANAGED_EXECUTOR_EXPLICIT_REQUEST_REQUIRED");
        }
        if (productionMutationRequested(trustedRequestInput)) {
            throw denied("MANAGED_EXECUTOR_PRODUCTION_MUTATION_REQUIRES_WORKFLOW");
        }
        requireExactArguments(modelArgs, Set.of("objective"));
        String objective = requiredContent(modelArgs.get("objective"), properties.maxObjectiveCharacters());
        String tenantId = tenantId(identity);
        String sourceRef = sourceRef(config, traceId);
        ExecutionView execution = executionService.create(
                tenantId,
                identity.userId(),
                new CreateRequest(
                        identity.projectCode(),
                        SOURCE_TYPE,
                        sourceRef,
                        "CODEX",
                        policy.sandboxProfile(),
                        policy.modelRef(),
                        policy.acceptanceProfile(),
                        objective,
                        policy.priority(),
                        policy.maxWallTimeSeconds(),
                        policy.approvalTimeoutSeconds()))
                .execution();
        requireOwnedExecution(execution, config, identity);
        return executionCard(execution, true);
    }

    private Map<String, Object> status(
            DelegationPolicy policy,
            RuntimeAgentConfigVersionEntity config,
            WorkflowExecutionIdentity identity,
            Map<String, Object> modelArgs) {
        requireExactArguments(modelArgs, Set.of("executionId"));
        ExecutionView execution = ownedExecution(
                requiredIdentifier(modelArgs.get("executionId"), "executionId", 128), config, identity);
        return executionCard(execution, false);
    }

    private Map<String, Object> readResult(
            DelegationPolicy policy,
            RuntimeAgentConfigVersionEntity config,
            WorkflowExecutionIdentity identity,
            Map<String, Object> modelArgs) {
        requireExactArguments(modelArgs, Set.of("executionId"));
        ExecutionView execution = ownedExecution(
                requiredIdentifier(modelArgs.get("executionId"), "executionId", 128), config, identity);
        if (!ManagedExecutionStatus.SUCCEEDED.name().equals(execution.status())) {
            throw denied("MANAGED_EXECUTOR_RESULT_NOT_READY");
        }
        List<ArtifactView> artifacts = artifactReadService.list(execution.executionId(), tenantId(identity));
        ArtifactView summaryArtifact = artifact(artifacts, "EXECUTION_SUMMARY");
        ArtifactView testArtifact = artifact(artifacts, "TEST_REPORT");
        ArtifactView patchArtifact = artifact(artifacts, "PATCH");
        JsonNode summary = readJson(execution.executionId(), summaryArtifact, identity);
        JsonNode tests = readJson(execution.executionId(), testArtifact, identity);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", RESULT_SCHEMA);
        result.put("kind", "MANAGED_EXECUTION_RESULT_REFERENCE");
        result.put("executionId", execution.executionId());
        result.put("status", execution.status());
        result.put("projectCode", execution.projectCode());
        result.put("summary", safeSummary(summary));
        result.put("tests", safeTests(tests));
        result.put("patchReference", artifactReference(patchArtifact));
        result.put("artifacts", artifacts.stream().map(this::artifactReference).toList());
        result.put("productionMutationApplied", false);
        return Map.copyOf(result);
    }

    private ExecutionView ownedExecution(
            String executionId,
            RuntimeAgentConfigVersionEntity config,
            WorkflowExecutionIdentity identity) {
        try {
            ExecutionView execution = executionService.get(executionId, tenantId(identity));
            requireOwnedExecution(execution, config, identity);
            return execution;
        } catch (ManagedExecutionException notVisible) {
            if (notVisible.status() == 403 || notVisible.status() == 404) {
                throw denied("MANAGED_EXECUTOR_REFERENCE_DENIED");
            }
            throw notVisible;
        }
    }

    private void requireOwnedExecution(
            ExecutionView execution,
            RuntimeAgentConfigVersionEntity config,
            WorkflowExecutionIdentity identity) {
        String expectedPrefix = sourcePrefix(config);
        if (execution == null
                || !SOURCE_TYPE.equals(execution.sourceType())
                || !tenantId(identity).equals(execution.tenantId())
                || !identity.projectCode().equalsIgnoreCase(execution.projectCode())
                || !identity.userId().equals(execution.requestedByUserId())
                || !StringUtils.hasText(execution.sourceRef())
                || !execution.sourceRef().startsWith(expectedPrefix)) {
            throw denied("MANAGED_EXECUTOR_REFERENCE_DENIED");
        }
    }

    private Map<String, Object> executionCard(ExecutionView execution, boolean started) {
        ManagedExecutionStatus status = ManagedExecutionStatus.parse(execution.status());
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("schema", CARD_SCHEMA);
        card.put("kind", "MANAGED_EXECUTION");
        card.put("executionId", execution.executionId());
        card.put("status", execution.status());
        card.put("projectCode", execution.projectCode());
        card.put("sandboxProfile", execution.sandboxProfile());
        card.put("acceptanceProfile", execution.acceptanceProfile());
        card.put("objectiveSha256", execution.objectiveSha256());
        card.put("async", true);
        card.put("startedByThisCall", started);
        card.put("terminal", status.terminal());
        card.put("resultAvailable", status == ManagedExecutionStatus.SUCCEEDED);
        card.put("lastEventSequence", execution.lastEventSequence());
        card.put("approvalCount", execution.approvalCount());
        putIfText(card, "pendingInteractionId", execution.pendingInteractionId());
        putIfSafeCode(card, "errorCode", execution.errorCode());
        card.put("nextAction", switch (status) {
            case SUCCEEDED -> "CALL_MANAGED_EXECUTOR_READ_RESULT";
            case FAILED, TIMED_OUT, CANCELLED -> "REPORT_TERMINAL_STATUS";
            default -> "RETURN_ASYNC_TASK_CARD";
        });
        card.put("productionMutationApplied", false);
        return Map.copyOf(card);
    }

    private JsonNode readJson(String executionId, ArtifactView artifact, WorkflowExecutionIdentity identity) {
        try {
            byte[] bytes = artifactReadService.read(
                    executionId, artifact.artifactId(), tenantId(identity)).bytes();
            JsonNode root = objectMapper.readTree(bytes);
            if (root == null || !root.isObject() || !executionId.equals(root.path("executionId").asText())) {
                throw denied("MANAGED_EXECUTOR_RESULT_EVIDENCE_INVALID");
            }
            return root;
        } catch (DelegationException failure) {
            throw failure;
        } catch (Exception invalid) {
            throw denied("MANAGED_EXECUTOR_RESULT_EVIDENCE_INVALID");
        }
    }

    private Map<String, Object> safeSummary(JsonNode root) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("outcome", safeEnum(root.path("outcome").asText(), Set.of("SUCCEEDED"), "UNKNOWN"));
        summary.put("verificationOutcome", safeEnum(
                root.path("verificationOutcome").asText(), Set.of("PASSED", "NOT_RUN"), "UNKNOWN"));
        summary.put("readonlyViolation", root.path("readonlyViolation").asBoolean(true));
        JsonNode patch = root.path("patch");
        if (patch.isObject()) {
            Map<String, Object> safePatch = new LinkedHashMap<>();
            safePatch.put("empty", patch.path("empty").asBoolean(false));
            safePatch.put("bytes", Math.max(0L, patch.path("bytes").asLong(0L)));
            String digest = patch.path("sha256").asText();
            if (digest.matches("[a-f0-9]{64}")) safePatch.put("sha256", digest);
            summary.put("patch", Map.copyOf(safePatch));
        }
        return Map.copyOf(summary);
    }

    private Map<String, Object> safeTests(JsonNode root) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String outcome : List.of("PASSED", "FAILED", "TIMED_OUT", "OUTPUT_LIMIT_EXCEEDED", "ERROR")) {
            counts.put(outcome, 0);
        }
        int commandCount = 0;
        JsonNode commands = root.path("commands");
        if (commands.isArray()) {
            for (JsonNode command : commands) {
                if (commandCount >= 100) break;
                commandCount++;
                String outcome = command.path("outcome").asText().toUpperCase(Locale.ROOT);
                if (counts.containsKey(outcome)) counts.put(outcome, counts.get(outcome) + 1);
            }
        }
        return Map.of(
                "outcome", safeEnum(root.path("outcome").asText(),
                        Set.of("PASSED", "FAILED", "NOT_RUN"), "UNKNOWN"),
                "commandCount", commandCount,
                "outcomeCounts", Map.copyOf(counts));
    }

    private Map<String, Object> artifactReference(ArtifactView artifact) {
        return Map.of(
                "artifactId", artifact.artifactId(),
                "artifactType", artifact.artifactType(),
                "sha256", artifact.sha256(),
                "sizeBytes", artifact.sizeBytes(),
                "mediaType", artifact.mediaType(),
                "validationStatus", artifact.validationStatus(),
                "scanStatus", artifact.scanStatus());
    }

    private ArtifactView artifact(List<ArtifactView> artifacts, String type) {
        return artifacts.stream()
                .filter(value -> type.equals(value.artifactType()))
                .findFirst()
                .orElseThrow(() -> denied("MANAGED_EXECUTOR_RESULT_EVIDENCE_INVALID"));
    }

    private void requireTool(DelegationPolicy policy, String toolName) {
        if (policy == null || !policy.enabled() || !policy.allowedTools().contains(toolName)) {
            throw denied("MANAGED_EXECUTOR_TOOL_NOT_ALLOWED");
        }
    }

    private void requireTrustedIdentity(WorkflowExecutionIdentity identity) {
        if (!trustedInteractiveIdentity(identity) || !properties.allowsProject(identity.projectCode())) {
            throw denied("MANAGED_EXECUTOR_TRUSTED_IDENTITY_REQUIRED");
        }
    }

    private boolean trustedInteractiveIdentity(WorkflowExecutionIdentity identity) {
        return identity != null
                && identity.projectTrusted()
                && identity.userTrusted()
                && StringUtils.hasText(identity.projectCode())
                && StringUtils.hasText(identity.userId())
                && (identity.source() == WorkflowExecutionIdentity.Source.AGENT
                    || identity.source() == WorkflowExecutionIdentity.Source.EMBED_SESSION);
    }

    private boolean explicitStartRequested(Map<String, Object> input) {
        return input != null && Boolean.TRUE.equals(input.get("managedExecutorRequested"));
    }

    private boolean productionMutationRequested(Map<String, Object> input) {
        if (input == null || input.isEmpty()) return false;
        Object raw = input.get("userInput");
        if (!(raw instanceof String) || !StringUtils.hasText((String) raw)) raw = input.get("message");
        if (!(raw instanceof String) || !StringUtils.hasText((String) raw)) raw = input.get("input");
        return raw instanceof String text && PRODUCTION_MUTATION_INTENT.matcher(text).find();
    }

    private Set<String> strictToolAllowlist(Object value) {
        if (!(value instanceof Collection<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("allowedTools is required");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : values) {
            if (!(item instanceof String name) || !TOOL_NAMES.contains(name) || !result.add(name)) {
                throw new IllegalArgumentException("allowedTools is invalid");
            }
        }
        return Set.copyOf(result);
    }

    private String requiredEnum(Object value, Set<String> allowed, String field) {
        String normalized = requiredIdentifier(value, field, 128).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException(field + " is invalid");
        return normalized;
    }

    private String requiredIdentifier(Object value, String field, int maximum) {
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            throw new IllegalArgumentException(field + " is required");
        }
        String result = text.trim();
        if (result.length() > maximum || !result.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return result;
    }

    private String optionalIdentifier(Object value, String field, int maximum) {
        if (value == null) return null;
        return requiredIdentifier(value, field, maximum);
    }

    private int requiredInteger(Object value, int minimum, int maximum, String field) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException(field + " is required");
        return exactInteger(number, minimum, maximum, field);
    }

    private int optionalInteger(Object value, int defaultValue, int minimum, int maximum, String field) {
        if (value == null) return defaultValue;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(field + " is invalid");
        return exactInteger(number, minimum, maximum, field);
    }

    private int exactInteger(Number number, int minimum, int maximum, String field) {
        long value = number.longValue();
        if (Double.compare(number.doubleValue(), (double) value) != 0
                || value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return (int) value;
    }

    private String requiredContent(Object value, int maximum) {
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            throw denied("MANAGED_EXECUTOR_OBJECTIVE_REQUIRED");
        }
        String result = text.trim();
        boolean forbiddenControl = result.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\r' && codePoint != '\t');
        if (result.codePointCount(0, result.length()) > maximum || forbiddenControl) {
            throw denied("MANAGED_EXECUTOR_OBJECTIVE_INVALID");
        }
        return result;
    }

    private void requireExactArguments(Map<String, Object> args, Set<String> expectedKeys) {
        if (args == null || !args.keySet().equals(expectedKeys)) {
            throw denied("MANAGED_EXECUTOR_ARGUMENTS_INVALID");
        }
    }

    private String sourceRef(RuntimeAgentConfigVersionEntity config, String traceId) {
        return sourcePrefix(config) + sha256(StringUtils.hasText(traceId) ? traceId : "missing-trace").substring(0, 40);
    }

    private String sourcePrefix(RuntimeAgentConfigVersionEntity config) {
        if (config == null || config.getId() == null) throw denied("MANAGED_EXECUTOR_CONFIG_REQUIRED");
        return "acv:" + config.getId() + ":trace:";
    }

    private String tenantId(WorkflowExecutionIdentity identity) {
        return StringUtils.hasText(identity.tenantId()) ? identity.tenantId().trim() : "default";
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String safeEnum(String value, Set<String> allowed, String fallback) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) target.put(key, value);
    }

    private void putIfSafeCode(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value) && value.matches("[A-Z0-9_]{1,128}")) target.put(key, value);
    }

    private DelegationException denied(String code) {
        return new DelegationException(code);
    }

    public record DelegationPolicy(
            boolean enabled,
            boolean autoRouteEnabled,
            Set<String> allowedTools,
            String sandboxProfile,
            String modelRef,
            String acceptanceProfile,
            int priority,
            int maxWallTimeSeconds,
            int approvalTimeoutSeconds,
            int maxDelegationsPerRun) {

        public DelegationPolicy {
            allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        }

        public static DelegationPolicy disabled() {
            return new DelegationPolicy(false, false, Set.of(), null, null, null, 0, 0, 0, 0);
        }
    }

    public static final class DelegationException extends RuntimeException {
        private final String code;

        public DelegationException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
