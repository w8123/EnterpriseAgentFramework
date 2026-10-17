package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicit, bounded wire contracts for the first Console HTTP API trial call. */
public final class HttpApiConsoleContracts {
    public static final int VERSION = 1;
    private HttpApiConsoleContracts() { }

    public record ExecutionContext(int contractVersion, Long apiId, String qualifiedName,
                                   Long projectId, String projectCode, String environment,
                                   String httpMethod, String routeTemplate,
                                   boolean sourceConfirmed, String sourceStatus, String sourceReason,
                                   String candidateContractHash, String acceptedContractHash,
                                   String sourceSetRevision, JsonNode acceptedContract,
                                   boolean firstCallSupported, String unsupportedReason,
                                   boolean consoleCallSupported, String consoleUnsupportedReason) {
        public ExecutionContext(int contractVersion, Long apiId, String qualifiedName,
                                Long projectId, String projectCode, String environment,
                                String httpMethod, String routeTemplate, boolean sourceConfirmed,
                                String sourceStatus, String sourceReason, String candidateContractHash,
                                String acceptedContractHash, String sourceSetRevision, JsonNode acceptedContract,
                                boolean firstCallSupported, String unsupportedReason) {
            this(contractVersion, apiId, qualifiedName, projectId, projectCode, environment, httpMethod,
                    routeTemplate, sourceConfirmed, sourceStatus, sourceReason, candidateContractHash,
                    acceptedContractHash, sourceSetRevision, acceptedContract, firstCallSupported, unsupportedReason,
                    firstCallSupported, unsupportedReason);
        }
    }

    public record ConnectionSaveRequest(String origin, String authMode, String credentialRef,
                                        Long expectedRevision) {
        public static ConnectionSaveRequest fromWire(Map<String, Object> wire) {
            if (wire == null || !Set.of("origin", "authMode", "credentialRef", "expectedRevision")
                    .containsAll(wire.keySet())) throw new IllegalArgumentException("invalid connection fields");
            Object revision = wire.get("expectedRevision");
            if (revision != null && !(revision instanceof Number)) throw new IllegalArgumentException("invalid revision");
            return new ConnectionSaveRequest(text(wire.get("origin")), text(wire.get("authMode")),
                    text(wire.get("credentialRef")), revision == null ? null : ((Number) revision).longValue());
        }
    }

    /**
     * Effective connection for one API. {@code revision} is the opaque effective revision that invocations pin;
     * {@code connectionSource} says whether it comes from this API's override or the project default.
     * {@code overrideRevision} is the optimistic revision for saving/removing this API's override and
     * {@code projectDefault.revision} the one for saving the project default.
     */
    public record ConnectionView(String qualifiedName, Long projectId, String projectCode,
                                 String environment, String origin, String authMode,
                                 String credentialRef, String credentialName, String credentialRevision, Long revision,
                                 String status, String blockingReason, String previewUrl,
                                 MarketVerificationView verification, boolean configurationAllowed,
                                 String connectionSource, boolean inherited, boolean overrideActive,
                                 Long overrideRevision, ProjectDefaultConnectionView projectDefault) {
        public static final String SOURCE_OVERRIDE = "OVERRIDE";
        public static final String SOURCE_PROJECT_DEFAULT = "PROJECT_DEFAULT";

        /** Per-API-only shape kept for existing integrations: the saved row is treated as an active override. */
        public ConnectionView(String qualifiedName, Long projectId, String projectCode, String environment,
                              String origin, String authMode, String credentialRef, String credentialName,
                              String credentialRevision, Long revision, String status, String blockingReason,
                              String previewUrl, MarketVerificationView verification, boolean configurationAllowed) {
            this(qualifiedName, projectId, projectCode, environment, origin, authMode, credentialRef,
                    credentialName, credentialRevision, revision, status, blockingReason, previewUrl, verification,
                    configurationAllowed, revision == null ? null : SOURCE_OVERRIDE, false, revision != null,
                    revision, null);
        }
        /** Source-compatible constructor for existing integrations; Runtime returns explicit owner facts. */
        public ConnectionView(String qualifiedName, Long projectId, String projectCode, String environment,
                              String origin, String authMode, String credentialRef, String credentialName,
                              String credentialRevision, Long revision, String status, String blockingReason,
                              String previewUrl, MarketVerificationView verification) {
            this(qualifiedName, projectId, projectCode, environment, origin, authMode, credentialRef,
                    credentialName, credentialRevision, revision, status, blockingReason, previewUrl, verification,
                    "CONFIGURED".equals(status) || "UNCONFIGURED".equals(status));
        }
        public ConnectionView(String qualifiedName, Long projectId, String projectCode, String environment,
                              String origin, String authMode, String credentialRef, String credentialName,
                              String credentialRevision, Long revision, String status, String blockingReason,
                              String previewUrl) {
            this(qualifiedName, projectId, projectCode, environment, origin, authMode, credentialRef,
                    credentialName, credentialRevision, revision, status, blockingReason, previewUrl, null);
        }
    }

    /** Project + environment default connection inherited by every API without an active override. */
    public record ProjectDefaultConnectionView(Long projectId, String projectCode, String environment,
                                               String origin, String authMode, String credentialRef,
                                               String credentialName, Long revision, String status,
                                               String blockingReason, boolean configurationAllowed) { }

    /** Derived from the existing Runtime Console ledger and root Run; never submitted by the browser. */
    public record MarketVerificationView(String status, String reason, Long apiId, String acceptedContractHash,
                                         String sourceSetRevision, Long connectionRevision, String credentialRevision,
                                         String invocationId, Long runId, String traceId, String verifiedAt) { }

    public record ConnectionCommand(int contractVersion, Long apiId, String qualifiedName,
                                    Long projectId, String projectCode, String environment,
                                    ConnectionSaveRequest save) { }

    public record PublicInvocationRequest(String invocationId, String expectedContractHash,
                                          Long connectionRevision, Map<String, Object> pathParams,
                                          Map<String, Object> queryParams, Object body,
                                          boolean confirmedSideEffect, String expectedSourceSetRevision,
                                          String expectedCredentialRevision) {
        public static PublicInvocationRequest fromWire(Map<String, Object> wire) {
            if (wire == null || !Set.of("invocationId", "expectedContractHash", "connectionRevision",
                    "pathParams", "queryParams", "body", "confirmedSideEffect", "expectedSourceSetRevision", "expectedCredentialRevision")
                    .containsAll(wire.keySet())) {
                throw new IllegalArgumentException("invalid invocation fields");
            }
            String id = text(wire.get("invocationId"));
            try {
                if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("invalid invocation id");
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("invalid invocation id", invalid);
            }
            String hash = text(wire.get("expectedContractHash"));
            if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid contract hash");
            Object revision = wire.get("connectionRevision");
            if (!(revision instanceof Number number) || number.longValue() <= 0
                    || number.doubleValue() != number.longValue()) throw new IllegalArgumentException("invalid connection revision");
            if (wire.containsKey("confirmedSideEffect") && !(wire.get("confirmedSideEffect") instanceof Boolean)) {
                throw new IllegalArgumentException("invalid side effect confirmation");
            }
            String sourceRevision = text(wire.get("expectedSourceSetRevision"));
            if (wire.containsKey("expectedSourceSetRevision") && (sourceRevision == null
                    || !sourceRevision.matches("[a-f0-9]{64}"))) throw new IllegalArgumentException("invalid source revision");
            String credentialRevision = text(wire.get("expectedCredentialRevision"));
            if (wire.get("expectedCredentialRevision") != null && (credentialRevision == null
                    || !credentialRevision.matches("[a-f0-9]{64}"))) throw new IllegalArgumentException("invalid credential revision");
            return new PublicInvocationRequest(id, hash, number.longValue(), values(wire.get("pathParams")),
                    values(wire.get("queryParams")), wire.get("body") == null ? null : HttpApiJsonBinding.copyWire(wire.get("body"), new com.fasterxml.jackson.databind.ObjectMapper()),
                    Boolean.TRUE.equals(wire.get("confirmedSideEffect")), sourceRevision, credentialRevision);
        }
    }

    public record InvocationCommand(int contractVersion, String invocationId, String platformActorId,
                                    Long apiId, String qualifiedName, Long projectId, String projectCode,
                                    String environment, String expectedContractHash,
                                    String expectedSourceSetRevision, Long connectionRevision,
                                    Map<String, Object> pathParams, Map<String, Object> queryParams,
                                    long deadlineEpochMs, Object body, boolean confirmedSideEffect,
                                    String expectedCredentialRevision) {
        public InvocationCommand(int contractVersion, String invocationId, String platformActorId,
                                 Long apiId, String qualifiedName, Long projectId, String projectCode,
                                 String environment, String expectedContractHash, String expectedSourceSetRevision,
                                 Long connectionRevision, Map<String, Object> pathParams, Map<String, Object> queryParams,
                                 long deadlineEpochMs) {
            this(contractVersion, invocationId, platformActorId, apiId, qualifiedName, projectId, projectCode,
                    environment, expectedContractHash, expectedSourceSetRevision, connectionRevision,
                    pathParams, queryParams, deadlineEpochMs, null, false, null);
        }
    }

    public record InvocationOutcome(int contractVersion, String invocationId, String targetType,
                                    String qualifiedName, Long projectId, String projectCode,
                                    String environment, Long runId, String traceId,
                                    String status, String dispatchStage, boolean terminal,
                                    String errorCode, Integer httpStatus, Long latencyMs,
                                    Object result, boolean resultTruncated, Long resultExpiresAt,
                                    String expectedContractHash, String sourceSetRevision,
                                    Long connectionRevision, String credentialRevision) { }

    private static Map<String, Object> values(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> map) || map.size() > 64) throw new IllegalArgumentException("invalid parameters");
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (!(key instanceof String name) || name.isBlank() || name.length() > 128) {
                throw new IllegalArgumentException("invalid parameter name");
            }
            out.put(name, value);
        });
        // Preserve null so the policy can give the same required/optional behavior for JSON null
        // as for an absent parameter. Map.copyOf would throw before validation and lose the field.
        return java.util.Collections.unmodifiableMap(out);
    }

    private static String text(Object value) {
        return value instanceof String source && !source.isBlank() ? source.trim() : null;
    }
}
