package com.enterprise.ai.runtime.bizindex;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.contract.bizindex.BusinessIndexReference;
import com.enterprise.ai.runtime.contract.bizindex.BusinessIndexResolution;
import com.enterprise.ai.runtime.execution.RuntimeBusinessIndexResolutionPort;
import com.enterprise.ai.runtime.execution.RuntimeBusinessIndexResolutionPort.HydrationBatch;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts Knowledge business-index hits into current, resolver-authorized data.
 * Any index row that advertises the Agent business-index reference contract is stripped before it
 * reaches a Workflow/LLM; failed hydration never falls back to copied index text.
 */
@Service
public class RuntimeBusinessIndexResolutionService implements RuntimeBusinessIndexResolutionPort {

    private static final String PREFIX = "reachai.business_index.resolution";
    private static final int MAX_SCAN_DEPTH = 12;
    private static final int MAX_RESOLUTION_SEARCH_DEPTH = 6;

    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final int maxReferences;
    private final int maxResolutionBytes;

    public RuntimeBusinessIndexResolutionService(
            RuntimeCapabilityCatalogClient capabilityClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${reachai.runtime.business-index.max-references:8}") int maxReferences,
            @Value("${reachai.runtime.business-index.max-resolution-bytes:262144}") int maxResolutionBytes) {
        this.capabilityClient = capabilityClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.maxReferences = Math.max(1, Math.min(maxReferences, 32));
        this.maxResolutionBytes = Math.max(4_096, Math.min(maxResolutionBytes, 2_097_152));
    }

    public HydrationBatch hydrate(Object input,
                                  WorkflowExecutionIdentity identity,
                                  Map<String, Object> executionContext) {
        State state = new State();
        Object output = transform(input, 0, state,
                identity == null ? WorkflowExecutionIdentity.untrustedDebug() : identity,
                executionContext == null ? Map.of() : executionContext);
        if (state.detected > 0) {
            meterRegistry.summary(PREFIX + ".batch_references").record(state.detected);
        }
        return new HydrationBatch(output, state.detected > 0, state.detected,
                state.resolved, state.blocked, state.versionChanged);
    }

    private Object transform(Object value,
                             int depth,
                             State state,
                             WorkflowExecutionIdentity identity,
                             Map<String, Object> executionContext) {
        if (value == null || depth > MAX_SCAN_DEPTH) return value;
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = stringMap(raw);
            if (looksLikeBusinessIndexHit(map)) {
                return hydrateHit(map, state, identity, executionContext);
            }
            Map<String, Object> transformed = new LinkedHashMap<>();
            map.forEach((key, item) -> transformed.put(
                    key, transform(item, depth + 1, state, identity, executionContext)));
            return transformed;
        }
        if (value instanceof List<?> list) {
            List<Object> transformed = new ArrayList<>(list.size());
            for (Object item : list) {
                transformed.add(transform(item, depth + 1, state, identity, executionContext));
            }
            return transformed;
        }
        return value;
    }

    private boolean looksLikeBusinessIndexHit(Map<String, Object> map) {
        if (map.containsKey("businessIndexReference") || map.containsKey("agentReferenceEligible")) {
            return true;
        }
        // Fail closed for the legacy business-index search shape as well. This prevents
        // a Workflow from bypassing eligibility simply by calling an older search route.
        return map.containsKey("bizId")
                && map.containsKey("matchContent")
                && map.containsKey("matchSource");
    }

    private Map<String, Object> hydrateHit(Map<String, Object> hit,
                                           State state,
                                           WorkflowExecutionIdentity identity,
                                           Map<String, Object> executionContext) {
        state.detected++;
        boolean eligible = Boolean.TRUE.equals(booleanValue(hit.get("agentReferenceEligible")));
        if (!eligible) {
            state.blocked++;
            recordOutcome("not_agent_eligible");
            return safeFailure(hit, false, "NOT_AGENT_ELIGIBLE");
        }

        BusinessIndexReference reference;
        try {
            reference = reference(hit.get("businessIndexReference"));
        } catch (RuntimeException invalid) {
            state.blocked++;
            recordOutcome("invalid_reference");
            return safeFailure(hit, true, "INVALID_REFERENCE");
        }
        if (state.resolverCalls >= maxReferences) {
            state.blocked++;
            recordOutcome("limit_exceeded");
            return safeFailure(hit, true, "LIMIT_EXCEEDED");
        }
        if (!trustedFor(reference, identity)) {
            state.blocked++;
            recordOutcome("scope_denied");
            return safeFailure(hit, true, "SCOPE_DENIED");
        }

        state.resolverCalls++;
        long started = System.nanoTime();
        try {
            Map<String, Object> response = capabilityClient.executeTool(
                    reference.resolverCapabilityKey(), resolverRequest(reference, identity, executionContext));
            if (response == null || Boolean.FALSE.equals(booleanValue(response.get("success")))) {
                throw new IllegalStateException("resolver rejected business index hydration");
            }
            String resolvedBy = text(response.get("qualifiedName"));
            if (StringUtils.hasText(resolvedBy)
                    && !reference.resolverCapabilityKey().equals(resolvedBy)) {
                throw new IllegalStateException("resolver returned a different qualified name");
            }
            BusinessIndexResolution resolution = resolution(response);
            validateResolution(reference, identity, resolution);
            if (objectMapper.writeValueAsBytes(resolution.data()).length > maxResolutionBytes) {
                state.blocked++;
                recordOutcome("response_too_large");
                return safeFailure(hit, true, "RESPONSE_TOO_LARGE");
            }
            boolean versionMatched = reference.sourceVersion().equals(resolution.sourceVersion());
            state.resolved++;
            if (!versionMatched) state.versionChanged++;
            recordOutcome(versionMatched ? "resolved" : "resolved_version_changed");
            return safeSuccess(hit, reference, resolution, versionMatched);
        } catch (Exception rejected) {
            state.blocked++;
            recordOutcome("resolver_rejected");
            return safeFailure(hit, true, "RESOLVER_REJECTED");
        } finally {
            meterRegistry.timer(PREFIX + ".latency", "provider", "capability")
                    .record(Duration.ofNanos(Math.max(0L, System.nanoTime() - started)));
        }
    }

    private boolean trustedFor(BusinessIndexReference reference,
                               WorkflowExecutionIdentity identity) {
        return identity.canResolveProjectCredential()
                && identity.canResolveUserAcl()
                && StringUtils.hasText(identity.tenantId())
                && StringUtils.hasText(identity.projectCode())
                && reference.tenantId().equalsIgnoreCase(identity.tenantId())
                && reference.projectCode().equalsIgnoreCase(identity.projectCode());
    }

    private Map<String, Object> resolverRequest(BusinessIndexReference reference,
                                                WorkflowExecutionIdentity identity,
                                                Map<String, Object> executionContext) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantId", identity.tenantId());
        context.put("projectCode", identity.projectCode());
        context.put("externalUserId", identity.userId());
        copySafeContext(executionContext, context, "agentId", "agentDefinitionId");
        copySafeContext(executionContext, context, "sessionId", "sessionId");
        copySafeContext(executionContext, context, "supervisorTraceId", "supervisorTraceId");
        copySafeContext(executionContext, context, "traceId", "supervisorTraceId");

        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("expectedQualifiedName", reference.resolverCapabilityKey());
        constraints.put("expectedProjectCode", reference.projectCode());
        constraints.put("requireUserIdentity", true);
        constraints.put("requireSignedInvocation", true);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("input", reference.resolverArguments());
        request.put("context", context);
        request.put("constraints", constraints);
        request.put(RuntimeCapabilityCatalogClient.TRUSTED_IDENTITY_ATTRIBUTE, identity);
        return request;
    }

    private void copySafeContext(Map<String, Object> source,
                                 Map<String, Object> target,
                                 String sourceKey,
                                 String targetKey) {
        String value = text(source.get(sourceKey));
        if (StringUtils.hasText(value) && value.length() <= 256) {
            target.putIfAbsent(targetKey, value);
        }
    }

    private BusinessIndexReference reference(Object raw) {
        if (raw instanceof BusinessIndexReference reference) return reference;
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("businessIndexReference must be an object");
        }
        Map<String, Object> value = stringMap(map);
        return new BusinessIndexReference(
                text(value.get("schema")),
                text(value.get("tenantId")),
                text(value.get("projectCode")),
                text(value.get("sourceSystem")),
                text(value.get("resourceType")),
                text(value.get("resourceId")),
                text(value.get("sourceVersion")),
                text(value.get("resolverCapabilityKey")),
                OffsetDateTime.parse(text(value.get("observedAt"))));
    }

    private BusinessIndexResolution resolution(Object raw) {
        Object candidate = findResolution(raw, 0);
        if (candidate instanceof BusinessIndexResolution resolution) return resolution;
        if (!(candidate instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("resolver did not return a business index resolution");
        }
        Map<String, Object> value = stringMap(map);
        Object rawData = value.get("data");
        if (!(rawData instanceof Map<?, ?> data)) {
            throw new IllegalArgumentException("business index resolution data is required");
        }
        return new BusinessIndexResolution(
                text(value.get("schema")),
                text(value.get("tenantId")),
                text(value.get("projectCode")),
                text(value.get("sourceSystem")),
                text(value.get("resourceType")),
                text(value.get("resourceId")),
                text(value.get("sourceVersion")),
                stringMap(data),
                OffsetDateTime.parse(text(value.get("resolvedAt"))));
    }

    private Object findResolution(Object value, int depth) {
        if (value == null || depth > MAX_RESOLUTION_SEARCH_DEPTH) return null;
        if (value instanceof BusinessIndexResolution) return value;
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = stringMap(raw);
            if (BusinessIndexResolution.SCHEMA.equals(text(map.get("schema")))) return map;
            for (Object nested : map.values()) {
                Object found = findResolution(nested, depth + 1);
                if (found != null) return found;
            }
        } else if (value instanceof List<?> list) {
            for (Object nested : list) {
                Object found = findResolution(nested, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void validateResolution(BusinessIndexReference reference,
                                    WorkflowExecutionIdentity identity,
                                    BusinessIndexResolution resolution) {
        if (!resolution.tenantId().equalsIgnoreCase(identity.tenantId())
                || !resolution.projectCode().equalsIgnoreCase(identity.projectCode())
                || !resolution.tenantId().equalsIgnoreCase(reference.tenantId())
                || !resolution.projectCode().equalsIgnoreCase(reference.projectCode())
                || !resolution.sourceSystem().equalsIgnoreCase(reference.sourceSystem())
                || !resolution.resourceType().equalsIgnoreCase(reference.resourceType())
                || !resolution.resourceId().equals(reference.resourceId())) {
            throw new IllegalArgumentException("business index resolution scope or resource mismatch");
        }
    }

    private Map<String, Object> safeFailure(Map<String, Object> hit,
                                            boolean eligible,
                                            String status) {
        Map<String, Object> safe = safeHints(hit);
        safe.put("agentReferenceEligible", eligible);
        safe.put("authoritative", false);
        safe.put("hydrationRequired", eligible);
        safe.put("hydrationStatus", status);
        return safe;
    }

    private Map<String, Object> safeSuccess(Map<String, Object> hit,
                                            BusinessIndexReference reference,
                                            BusinessIndexResolution resolution,
                                            boolean versionMatched) {
        Map<String, Object> safe = safeHints(hit);
        safe.put("resourceType", reference.resourceType());
        safe.put("resourceId", reference.resourceId());
        safe.put("businessIndexReference", reference);
        safe.put("businessIndexResolution", resolution);
        safe.put("agentReferenceEligible", true);
        safe.put("authoritative", true);
        safe.put("hydrationRequired", false);
        safe.put("hydrationStatus", versionMatched ? "RESOLVED" : "RESOLVED_VERSION_CHANGED");
        safe.put("indexVersionMatched", versionMatched);
        return safe;
    }

    private Map<String, Object> safeHints(Map<String, Object> hit) {
        Map<String, Object> safe = new LinkedHashMap<>();
        Object score = hit.get("score");
        if (score instanceof Number) safe.put("score", score);
        return safe;
    }

    private void recordOutcome(String outcome) {
        meterRegistry.counter(PREFIX + ".references",
                "provider", "capability", "outcome", outcome.toLowerCase(Locale.ROOT)).increment();
    }

    private Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value == null) return null;
        if ("true".equalsIgnoreCase(String.valueOf(value))) return true;
        if ("false".equalsIgnoreCase(String.valueOf(value))) return false;
        return null;
    }

    private String text(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim();
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private static final class State {
        private int detected;
        private int resolverCalls;
        private int resolved;
        private int blocked;
        private int versionChanged;
    }
}
