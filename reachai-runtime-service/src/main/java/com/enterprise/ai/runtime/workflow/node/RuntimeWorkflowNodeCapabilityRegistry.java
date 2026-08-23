package com.enterprise.ai.runtime.workflow.node;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Product openness policy for Workflow GraphSpec node types.
 *
 * <p>{@link RuntimeGraphSpecExecutor#handledNodeTypes()} is the runtime-executable truth source.
 * This registry decides which executable types are publishable, Studio-visible and AI-authorable.</p>
 */
@Component
public class RuntimeWorkflowNodeCapabilityRegistry {

    private static final String PLANNED_REASON = "Runtime Handler is not implemented for this node type";
    private final List<RuntimeWorkflowNodeCapabilityDescriptor> catalog;
    private final Map<String, RuntimeWorkflowNodeCapabilityDescriptor> byLookupKey;

    public RuntimeWorkflowNodeCapabilityRegistry() {
        Set<String> handled = RuntimeGraphSpecExecutor.handledNodeTypes();
        Map<AgentGraphNodeType, Policy> policies = defaultPolicies();
        if (policies.size() != AgentGraphNodeType.values().length) {
            throw new IllegalStateException("Workflow node capability policies must cover every AgentGraphNodeType");
        }
        Map<String, AgentGraphNodeType.Descriptor> protocolByType = new LinkedHashMap<>();
        for (AgentGraphNodeType.Descriptor protocol : AgentGraphNodeType.catalog()) {
            protocolByType.put(protocol.type(), protocol);
        }

        List<RuntimeWorkflowNodeCapabilityDescriptor> built = Arrays.stream(AgentGraphNodeType.values())
                .map(type -> toDescriptor(
                        protocolByType.get(type.type()),
                        policies.get(type),
                        handled.contains(type.type())))
                .sorted(Comparator.comparing(RuntimeWorkflowNodeCapabilityDescriptor::type))
                .toList();
        validate(built);

        this.catalog = List.copyOf(built);
        Map<String, RuntimeWorkflowNodeCapabilityDescriptor> lookup = new LinkedHashMap<>();
        for (RuntimeWorkflowNodeCapabilityDescriptor descriptor : this.catalog) {
            putLookup(lookup, descriptor.type(), descriptor);
            putLookup(lookup, descriptor.canvasKind(), descriptor);
        }
        this.byLookupKey = Map.copyOf(lookup);
    }

    public List<RuntimeWorkflowNodeCapabilityDescriptor> allCatalog() {
        return catalog;
    }

    public List<RuntimeWorkflowNodeCapabilityDescriptor> studioCatalog() {
        return catalog.stream()
                .filter(item -> item.runtimeExecutable() && item.studioEnabled())
                .toList();
    }

    public List<RuntimeWorkflowNodeCapabilityDescriptor> aiAuthoringCatalog() {
        return catalog.stream()
                .filter(item -> item.runtimeExecutable() && item.aiAuthoringEnabled())
                .toList();
    }

    public Optional<RuntimeWorkflowNodeCapabilityDescriptor> find(String rawType) {
        if (!StringUtils.hasText(rawType)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byLookupKey.get(lookupKey(rawType)));
    }

    public boolean isAiAuthoringEnabled(String rawType) {
        return find(rawType).map(RuntimeWorkflowNodeCapabilityDescriptor::aiAuthoringEnabled).orElse(false);
    }

    /**
     * Variant-aware authoring gate. An empty enabledVariants list means the node type has no
     * variant restriction; INTERACTION currently exposes only display-only PRESENT_OUTPUT.
     */
    public boolean isAiAuthoringEnabled(String rawType, Map<String, Object> config) {
        return find(rawType)
                .map(item -> item.aiAuthoringEnabled() && isEnabledVariant(item, config))
                .orElse(false);
    }

    public boolean isStudioEnabled(String rawType) {
        return find(rawType).map(item -> item.runtimeExecutable() && item.studioEnabled()).orElse(false);
    }

    public boolean isPublishable(String rawType) {
        return find(rawType).map(RuntimeWorkflowNodeCapabilityDescriptor::publishable).orElse(false);
    }

    /** Type-level openness plus an explicitly enabled variant such as INTERACTION/PRESENT_OUTPUT. */
    public boolean isPublishable(String rawType, Map<String, Object> config) {
        return find(rawType)
                .map(item -> item.publishable() || isExplicitlyEnabledVariant(item, config))
                .orElse(false);
    }

    public String variantUnavailableReason(String rawType, Map<String, Object> config) {
        RuntimeWorkflowNodeCapabilityDescriptor descriptor = find(rawType).orElse(null);
        if (descriptor == null) {
            return "unknown node type";
        }
        String variant = variantOf(descriptor, config);
        if (!StringUtils.hasText(variant)) {
            return descriptor.type() + " requires an explicit enabled variant: "
                    + String.join(", ", descriptor.enabledVariants());
        }
        return descriptor.type() + " variant is not enabled: " + variant
                + "; enabled variants: " + String.join(", ", descriptor.enabledVariants());
    }

    private static RuntimeWorkflowNodeCapabilityDescriptor toDescriptor(AgentGraphNodeType.Descriptor protocol,
                                                                        Policy policy,
                                                                        boolean runtimeExecutable) {
        if (protocol == null) {
            throw new IllegalStateException("Missing AgentGraphNodeType protocol descriptor");
        }
        String unavailableReason = policy.unavailableReason();
        if (!runtimeExecutable && !StringUtils.hasText(unavailableReason)) {
            unavailableReason = PLANNED_REASON;
        }
        return new RuntimeWorkflowNodeCapabilityDescriptor(
                protocol.type(),
                protocol.canvasKind(),
                protocol.canvasCategory(),
                protocol.family(),
                protocol.retryable(),
                policy.maturity(),
                runtimeExecutable,
                policy.publishable(),
                policy.studioEnabled(),
                policy.aiAuthoringEnabled(),
                policy.enabledVariants(),
                unavailableReason);
    }

    private static Map<AgentGraphNodeType, Policy> defaultPolicies() {
        Map<AgentGraphNodeType, Policy> policies = new EnumMap<>(AgentGraphNodeType.class);
        Policy stable = Policy.stable();
        for (AgentGraphNodeType type : List.of(
                AgentGraphNodeType.USER_INPUT,
                AgentGraphNodeType.INTENT_CLASSIFIER,
                AgentGraphNodeType.IF_ELSE,
                AgentGraphNodeType.PARAMETER_EXTRACT,
                AgentGraphNodeType.LLM,
                AgentGraphNodeType.TOOL,
                AgentGraphNodeType.ANSWER,
                AgentGraphNodeType.VARIABLE_ASSIGN,
                AgentGraphNodeType.TEMPLATE,
                AgentGraphNodeType.VARIABLE_AGGREGATOR)) {
            policies.put(type, stable);
        }
        policies.put(AgentGraphNodeType.PAGE_ACTION, Policy.betaOpen());
        // Fifth-round security scope closed; keep BETA with Browser/Live E2E still PENDING.
        policies.put(AgentGraphNodeType.KNOWLEDGE_RETRIEVAL, Policy.betaOpen());
        policies.put(AgentGraphNodeType.HTTP_REQUEST, Policy.betaOpen());
        // Temporarily open for Browser/Live LOOP E2E; will close again if E2E fails.
        policies.put(AgentGraphNodeType.LOOP, Policy.betaOpen());
        // PRESENT_OUTPUT never pauses a run and is independently open. Blocking interaction
        // variants stay closed until Agent/Embed + RunOps pause/resume Production Live E2E passes.
        policies.put(AgentGraphNodeType.INTERACTION, Policy.betaVariantOpen(
                List.of("PRESENT_OUTPUT"),
                "Only display-only INTERACTION/PRESENT_OUTPUT is enabled; blocking interaction pause/resume variants remain closed"));
        for (AgentGraphNodeType type : AgentGraphNodeType.values()) {
            policies.putIfAbsent(type, Policy.planned(PLANNED_REASON));
        }
        return policies;
    }

    private static void validate(List<RuntimeWorkflowNodeCapabilityDescriptor> descriptors) {
        Set<String> types = new java.util.LinkedHashSet<>();
        Set<String> canvasKinds = new java.util.LinkedHashSet<>();
        for (RuntimeWorkflowNodeCapabilityDescriptor item : descriptors) {
            if (!types.add(item.type())) {
                throw new IllegalStateException("Duplicate workflow node capability type: " + item.type());
            }
            if (!canvasKinds.add(item.canvasKind())) {
                throw new IllegalStateException("Duplicate workflow node canvasKind: " + item.canvasKind());
            }
            if (item.maturity() == WorkflowNodeMaturity.STABLE && !item.runtimeExecutable()) {
                throw new IllegalStateException("STABLE node must be runtimeExecutable: " + item.type());
            }
            if (item.publishable() && !item.runtimeExecutable()) {
                throw new IllegalStateException("publishable node must be runtimeExecutable: " + item.type());
            }
            if (item.aiAuthoringEnabled() && !item.runtimeExecutable()) {
                throw new IllegalStateException("aiAuthoringEnabled node must be runtimeExecutable: " + item.type());
            }
            if (item.studioEnabled() && !item.runtimeExecutable()) {
                throw new IllegalStateException("studioEnabled node must be runtimeExecutable: " + item.type());
            }
            if (!item.enabledVariants().isEmpty()
                    && (!item.runtimeExecutable() || !item.studioEnabled() || !item.aiAuthoringEnabled())) {
                throw new IllegalStateException("enabledVariants require executable Studio/AI openness: " + item.type());
            }
            if (item.maturity() == WorkflowNodeMaturity.PLANNED
                    && (item.publishable() || item.studioEnabled() || item.aiAuthoringEnabled() || item.runtimeExecutable())) {
                throw new IllegalStateException("PLANNED node must stay closed: " + item.type());
            }
        }
        if (types.size() != AgentGraphNodeType.values().length) {
            throw new IllegalStateException("Workflow node capability catalog size mismatch");
        }
    }

    private static void putLookup(Map<String, RuntimeWorkflowNodeCapabilityDescriptor> lookup,
                                  String rawKey,
                                  RuntimeWorkflowNodeCapabilityDescriptor descriptor) {
        String key = lookupKey(rawKey);
        if (!StringUtils.hasText(key)) {
            return;
        }
        RuntimeWorkflowNodeCapabilityDescriptor existing = lookup.putIfAbsent(key, descriptor);
        if (existing != null && !existing.type().equals(descriptor.type())) {
            throw new IllegalStateException("Conflicting workflow node capability lookup key: " + key);
        }
    }

    private static String lookupKey(String rawType) {
        return rawType == null ? "" : rawType.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static boolean isEnabledVariant(RuntimeWorkflowNodeCapabilityDescriptor descriptor,
                                            Map<String, Object> config) {
        return descriptor.enabledVariants().isEmpty()
                || isExplicitlyEnabledVariant(descriptor, config);
    }

    private static boolean isExplicitlyEnabledVariant(RuntimeWorkflowNodeCapabilityDescriptor descriptor,
                                                       Map<String, Object> config) {
        if (descriptor.enabledVariants().isEmpty()) {
            return false;
        }
        String variant = variantOf(descriptor, config);
        return StringUtils.hasText(variant) && descriptor.enabledVariants().contains(variant);
    }

    private static String variantOf(RuntimeWorkflowNodeCapabilityDescriptor descriptor,
                                    Map<String, Object> rawConfig) {
        if (!"INTERACTION".equals(descriptor.type())) {
            return null;
        }
        Map<String, Object> config = rawConfig == null
                ? Map.of()
                : new LinkedHashMap<>(rawConfig);
        Object nested = config.get("interactionConfig");
        if (nested instanceof Map<?, ?> nestedMap) {
            nestedMap.forEach((key, value) -> {
                if (key != null) {
                    config.putIfAbsent(String.valueOf(key), value);
                }
            });
        }
        Object rawVariant = config.get("interactionType");
        if (rawVariant == null) rawVariant = config.get("mode");
        if (rawVariant == null) rawVariant = config.get("type");
        return rawVariant == null ? null : lookupKey(String.valueOf(rawVariant));
    }

    private record Policy(
            WorkflowNodeMaturity maturity,
            boolean publishable,
            boolean studioEnabled,
            boolean aiAuthoringEnabled,
            List<String> enabledVariants,
            String unavailableReason
    ) {
        static Policy stable() {
            return new Policy(WorkflowNodeMaturity.STABLE, true, true, true, List.of(), null);
        }

        static Policy betaOpen() {
            return new Policy(WorkflowNodeMaturity.BETA, true, true, true, List.of(), null);
        }

        static Policy betaVariantOpen(List<String> variants, String reason) {
            List<String> normalized = variants == null ? List.of() : variants.stream()
                    .filter(StringUtils::hasText)
                    .map(RuntimeWorkflowNodeCapabilityRegistry::lookupKey)
                    .distinct()
                    .toList();
            return new Policy(WorkflowNodeMaturity.BETA, false, true, true, normalized, reason);
        }

        static Policy betaNotOpen(String reason) {
            return new Policy(WorkflowNodeMaturity.BETA, false, false, false, List.of(), reason);
        }

        static Policy codeGatePending(String reason) {
            return new Policy(WorkflowNodeMaturity.CODE_GATE_PENDING, false, false, false, List.of(), reason);
        }

        static Policy planned(String reason) {
            return new Policy(WorkflowNodeMaturity.PLANNED, false, false, false, List.of(), reason);
        }
    }
}
