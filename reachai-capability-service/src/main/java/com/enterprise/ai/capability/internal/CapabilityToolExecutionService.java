package com.enterprise.ai.capability.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.common.response.BusinessResponseEnvelope;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationClaims;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
public class CapabilityToolExecutionService {

    private static final String SIGNED_EVAL_POLICY_FIELD = "evaluationPolicy";

    private final ToolDefinitionMapper toolDefinitionMapper;
    private final CapabilityHttpToolInvoker invoker;
    private final RegistrySecurityService registrySecurityService;
    private final CapabilitySourceContractGuard sourceContractGuard;

    @Autowired
    public CapabilityToolExecutionService(ToolDefinitionMapper toolDefinitionMapper,
                                          CapabilityHttpToolInvoker invoker,
                                          RegistrySecurityService registrySecurityService,
                                          CapabilitySourceContractGuard sourceContractGuard) {
        this.toolDefinitionMapper = toolDefinitionMapper;
        this.invoker = invoker;
        this.registrySecurityService = registrySecurityService;
        this.sourceContractGuard = sourceContractGuard;
    }

    public Map<String, Object> execute(String qualifiedName, Map<String, Object> request) {
        ToolDefinitionEntity tool = findTool(qualifiedName);
        if (!Boolean.TRUE.equals(tool.getEnabled())) {
            throw new IllegalStateException("Tool definition is disabled: " + qualifiedName);
        }
        validateEvaluationPolicy(tool, request);
        validateExecutionConstraints(tool, request);
        sourceContractGuard.validate(tool, mapValue(request == null ? null : request.get("constraints")));
        Map<String, Object> input = mapValue(request == null ? null : request.get("input"));
        input = input == null ? Map.of() : input;
        String method = StringUtils.hasText(tool.getHttpMethod()) ? tool.getHttpMethod().trim().toUpperCase() : "POST";
        String url = buildUrl(tool);
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("Tool definition endpoint is missing: " + qualifiedName);
        }
        if ("GET".equals(method) && !input.isEmpty()) {
            url = appendQuery(url, input);
        }
        Map<String, Object> invocationMetadata = invocationMetadata(tool, request);
        validateInvocationConstraints(request, invocationMetadata);
        CapabilityHttpToolInvocation invocation = new CapabilityHttpToolInvocation(
                method,
                url,
                "GET".equals(method) ? Map.of() : input,
                invocationMetadata);
        Map<String, Object> invoked = invoker.invoke(invocation);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("qualifiedName", tool.getQualifiedName());
        response.put("toolName", tool.getName());
        response.put("toolTitle", titleOrName(tool.getTitle(), tool.getName()));
        applyInvocationResult(response, invoked);
        return response;
    }

    public Map<String, Object> execute(ScanProjectToolEntity tool, Map<String, Object> request) {
        if (tool == null) {
            throw new IllegalArgumentException("Scan project tool not found");
        }
        if (!Boolean.TRUE.equals(tool.getEnabled())) {
            throw new IllegalStateException("Scan project tool is disabled: " + tool.getId());
        }
        Map<String, Object> input = mapValue(request == null ? null : request.get("input"));
        input = input == null ? Map.of() : input;
        String method = StringUtils.hasText(tool.getHttpMethod()) ? tool.getHttpMethod().trim().toUpperCase() : "POST";
        String url = buildUrl(tool);
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException("Scan project tool endpoint is missing: " + tool.getId());
        }
        if ("GET".equals(method) && !input.isEmpty()) {
            url = appendQuery(url, input);
        }
        ToolDefinitionEntity linkedTool = linkedToolForInvocation(tool, method, buildUrl(tool));
        if (linkedTool != null) {
            sourceContractGuard.validate(linkedTool, mapValue(request == null ? null : request.get("constraints")));
        }
        Map<String, Object> metadata = linkedTool == null
                ? new LinkedHashMap<>()
                : invocationMetadata(linkedTool, request);
        metadata.put("scanToolId", tool.getId());
        metadata.put("toolName", tool.getName());
        metadata.put("toolTitle", titleOrName(tool.getTitle(), tool.getName()));
        metadata.put("requestBodyType", nullToEmpty(tool.getRequestBodyType()));
        metadata.put("responseType", nullToEmpty(tool.getResponseType()));
        CapabilityHttpToolInvocation invocation = new CapabilityHttpToolInvocation(
                method,
                url,
                "GET".equals(method) ? Map.of() : input,
                metadata);
        Map<String, Object> invoked = invoker.invoke(invocation);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scanToolId", tool.getId());
        response.put("toolName", tool.getName());
        response.put("toolTitle", titleOrName(tool.getTitle(), tool.getName()));
        applyInvocationResult(response, invoked);
        return response;
    }

    /**
     * Runtime business-memory hydration uses these constraints to turn a mutable
     * catalog lookup into a fail-closed project/exact-tool boundary. Constraints
     * can only reduce what an invocation may do; they never grant access.
     */
    private void validateEvaluationPolicy(ToolDefinitionEntity tool,
                                          Map<String, Object> request) {
        Map<String, Object> evaluation = mapValue(
                request == null ? null : request.get(SIGNED_EVAL_POLICY_FIELD));
        if (evaluation == null || evaluation.isEmpty()) {
            return;
        }
        String mode = text(evaluation.get("mode"));
        if (!StringUtils.hasText(mode) || "NONE".equalsIgnoreCase(mode)) {
            return;
        }
        String sideEffect = text(tool == null ? null : tool.getSideEffect());
        String normalized = StringUtils.hasText(sideEffect)
                ? sideEffect.toUpperCase(Locale.ROOT) : "";
        if (!"READ".equals(normalized) && !"READ_ONLY".equals(normalized)) {
            throw new IllegalStateException(
                    "EVAL_SIDE_EFFECT_BLOCKED: Capability must explicitly declare sideEffect=READ_ONLY");
        }
    }

    private void validateExecutionConstraints(ToolDefinitionEntity tool,
                                              Map<String, Object> request) {
        Map<String, Object> constraints = mapValue(request == null ? null : request.get("constraints"));
        if (constraints == null || constraints.isEmpty()) return;
        String expectedQualifiedName = text(constraints.get("expectedQualifiedName"));
        if (StringUtils.hasText(expectedQualifiedName)
                && !expectedQualifiedName.equals(tool.getQualifiedName())) {
            throw new IllegalStateException("Tool does not match the required qualified name");
        }
        String expectedProjectCode = text(constraints.get("expectedProjectCode"));
        if (StringUtils.hasText(expectedProjectCode)
                && (!StringUtils.hasText(tool.getProjectCode())
                || !expectedProjectCode.equalsIgnoreCase(tool.getProjectCode().trim()))) {
            throw new IllegalStateException("Tool does not belong to the required project");
        }
        if (Boolean.TRUE.equals(booleanValue(constraints.get("requireUserIdentity")))) {
            Map<String, Object> context = mapValue(request == null ? null : request.get("context"));
            String externalUserId = text(context == null ? null : context.get("externalUserId"));
            String globalUserId = text(context == null ? null : context.get("globalUserId"));
            if (!StringUtils.hasText(externalUserId) && !StringUtils.hasText(globalUserId)) {
                throw new IllegalStateException("Tool invocation requires a current user identity");
            }
        }
    }

    private void validateInvocationConstraints(Map<String, Object> request,
                                               Map<String, Object> invocationMetadata) {
        Map<String, Object> constraints = mapValue(request == null ? null : request.get("constraints"));
        if (constraints == null
                || !Boolean.TRUE.equals(booleanValue(constraints.get("requireSignedInvocation")))) {
            return;
        }
        Object rawHeaders = invocationMetadata == null ? null : invocationMetadata.get("headers");
        if (!(rawHeaders instanceof Map<?, ?> headers)
                || !headers.containsKey(ReachAiInvocationToken.HEADER_NAME)) {
            throw new IllegalStateException("Tool invocation requires a signed business identity");
        }
    }

    private ToolDefinitionEntity linkedToolForInvocation(ScanProjectToolEntity scanTool,
                                                          String scanMethod,
                                                          String scanUrl) {
        if (scanTool.getGlobalToolDefinitionId() == null) {
            if (StringUtils.hasText(scanTool.getSourceQualifiedName())
                    || normalizedText(scanTool.getSourceLocation()).startsWith("sdk:")) {
                throw new IllegalStateException(
                        "SDK Tool is not linked to the signed Tool catalog; synchronize it before testing: "
                                + scanTool.getId());
            }
            return null;
        }
        ToolDefinitionEntity linkedTool = toolDefinitionMapper.selectById(scanTool.getGlobalToolDefinitionId());
        if (linkedTool == null) {
            throw new IllegalStateException("Linked Tool definition is missing; synchronize the scan Tool before testing: "
                    + scanTool.getId());
        }

        List<String> mismatches = new java.util.ArrayList<>();
        if (!Objects.equals(scanTool.getSourceQualifiedName(), linkedTool.getSourceQualifiedName())) {
            mismatches.add("sourceQualifiedName");
        }
        if (!Boolean.TRUE.equals(linkedTool.getEnabled())) {
            mismatches.add("enabled");
        }
        if (scanTool.getProjectId() == null || linkedTool.getProjectId() == null
                || !Objects.equals(scanTool.getProjectId(), linkedTool.getProjectId())) {
            mismatches.add("projectId");
        }
        if (!Objects.equals(normalizedText(scanTool.getName()), normalizedText(linkedTool.getName()))) {
            mismatches.add("name");
        }
        if (!Objects.equals(normalizedText(scanTool.getSourceLocation()), normalizedText(linkedTool.getSourceLocation()))) {
            mismatches.add("sourceLocation");
        }
        String sourceLocation = normalizedText(scanTool.getSourceLocation());
        if (sourceLocation.startsWith("sdk:")
                && (!StringUtils.hasText(linkedTool.getProjectCode())
                || !sourceLocation.startsWith("sdk:" + linkedTool.getProjectCode().trim() + ":"))) {
            mismatches.add("projectCode");
        }
        String linkedMethod = StringUtils.hasText(linkedTool.getHttpMethod())
                ? linkedTool.getHttpMethod().trim().toUpperCase()
                : "POST";
        if (!Objects.equals(scanMethod, linkedMethod)) {
            mismatches.add("httpMethod");
        }
        if (!Objects.equals(scanUrl, buildUrl(linkedTool))) {
            mismatches.add("endpoint");
        }
        if (!mismatches.isEmpty()) {
            throw new IllegalStateException("Linked Tool definition is out of sync ("
                    + String.join(", ", mismatches)
                    + "); synchronize it before signed testing: " + scanTool.getId());
        }
        return linkedTool;
    }

    private void applyInvocationResult(Map<String, Object> response, Map<String, Object> invoked) {
        Object businessBody = invoked == null ? null : invoked.get("body");
        Optional<BusinessResponseEnvelope.Failure> failure = BusinessResponseEnvelope.failure(businessBody);
        response.put("success", failure.isEmpty());
        response.put("data", businessBody);
        response.put("metadata", invoked == null ? Map.of() : invoked);
        failure.ifPresent(value -> {
            response.put("code", "CAPABILITY_BUSINESS_RESPONSE_FAILED");
            response.put("message", value.message());
            if (value.businessCode() != null) {
                response.put("businessCode", value.businessCode());
            }
        });
    }

    private ToolDefinitionEntity findTool(String qualifiedName) {
        if (!StringUtils.hasText(qualifiedName)) {
            throw new IllegalArgumentException("Tool definition not found: " + qualifiedName);
        }
        String key = qualifiedName.trim();
        ToolDefinitionEntity entity = toolDefinitionMapper.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                .eq(ToolDefinitionEntity::getQualifiedName, key)
                .last("limit 1"));
        if (entity == null) {
            entity = toolDefinitionMapper.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                    .eq(ToolDefinitionEntity::getName, key)
                    .last("limit 1"));
        }
        if (entity == null) {
            throw new IllegalArgumentException("Tool definition not found: " + key);
        }
        return entity;
    }

    private String buildUrl(ToolDefinitionEntity tool) {
        StringBuilder url = new StringBuilder();
        appendUrlPart(url, tool.getBaseUrl());
        appendUrlPart(url, tool.getContextPath());
        appendUrlPart(url, tool.getEndpointPath());
        return url.toString();
    }

    private Map<String, Object> invocationMetadata(ToolDefinitionEntity tool,
                                                   Map<String, Object> request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("qualifiedName", tool.getQualifiedName());
        metadata.put("toolName", tool.getName());
        metadata.put("toolTitle", titleOrName(tool.getTitle(), tool.getName()));
        metadata.put("requestBodyType", nullToEmpty(tool.getRequestBodyType()));
        metadata.put("responseType", nullToEmpty(tool.getResponseType()));
        copyInvocationControl(metadata, request, "invocationId");
        copyInvocationControl(metadata, request, "deadlineEpochMs");
        copyInvocationControl(metadata, request, "idempotencyKey");
        Map<String, Object> traceContext = mapValue(request == null ? null : request.get("traceContext"));
        if (traceContext != null && !traceContext.isEmpty()) {
            metadata.put("traceContext", new LinkedHashMap<>(traceContext));
        }
        if (registrySecurityService == null || !StringUtils.hasText(tool.getProjectCode())) {
            return metadata;
        }
        RegistryCredentialEntity credential = registrySecurityService
                .findPrimaryActiveCredential(tool.getProjectCode())
                .orElse(null);
        if (credential == null || !StringUtils.hasText(credential.getAppSecret())) {
            return metadata;
        }
        Map<String, Object> context = mapValue(request == null ? null : request.get("context"));
        context = context == null ? Map.of() : context;
        Map<String, Object> attributes = mapValue(context.get("attributes"));
        ReachAiInvocationClaims claims = ReachAiInvocationClaims.builder()
                .projectCode(tool.getProjectCode())
                .appKey(credential.getAppKey())
                .capabilityName(invocationCapabilityName(tool))
                .tenantId(text(context.get("tenantId")))
                .externalUserId(text(context.get("externalUserId")))
                .globalUserId(text(context.get("globalUserId")))
                .userName(text(context.get("userName")))
                .deptId(text(context.get("deptId")))
                .deptName(text(context.get("deptName")))
                .roles(stringList(context.get("roles")))
                .agentId(text(context.get("agentDefinitionId")))
                .sessionId(text(context.get("sessionId")))
                .traceId(text(context.get("supervisorTraceId")))
                .pageInstanceId(text(context.get("pageInstanceId")))
                .origin(text(context.get("origin")))
                .route(text(context.get("route")))
                .attributes(attributes == null ? Map.of() : attributes)
                .build();
        String token = ReachAiInvocationToken.sign(credential.getAppSecret(), claims,
                System.currentTimeMillis(), 60);
        metadata.put("headers", Map.of(ReachAiInvocationToken.HEADER_NAME, token));
        return metadata;
    }

    private void copyInvocationControl(Map<String, Object> metadata,
                                       Map<String, Object> request,
                                       String key) {
        Object value = request == null ? null : request.get(key);
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private String invocationCapabilityName(ToolDefinitionEntity tool) {
        if (tool != null && StringUtils.hasText(tool.getProjectCode())
                && StringUtils.hasText(tool.getSourceLocation())) {
            String prefix = "sdk:" + tool.getProjectCode().trim() + ":";
            String sourceLocation = tool.getSourceLocation().trim();
            if (sourceLocation.startsWith(prefix) && sourceLocation.length() > prefix.length()) {
                return sourceLocation.substring(prefix.length()).trim();
            }
        }
        return tool == null ? null : tool.getName();
    }

    private String titleOrName(String title, String name) {
        return StringUtils.hasText(title) ? title.trim() : nullToEmpty(name);
    }

    private String buildUrl(ScanProjectToolEntity tool) {
        StringBuilder url = new StringBuilder();
        appendUrlPart(url, tool.getBaseUrl());
        appendUrlPart(url, tool.getContextPath());
        appendUrlPart(url, tool.getEndpointPath());
        return url.toString();
    }

    private void appendUrlPart(StringBuilder url, String part) {
        if (!StringUtils.hasText(part)) {
            return;
        }
        String value = part.trim();
        if (url.isEmpty()) {
            url.append(trimTrailingSlash(value));
            return;
        }
        url.append('/').append(trimSlashes(value));
    }

    private String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String trimSlashes(String value) {
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String appendQuery(String url, Map<String, Object> input) {
        StringBuilder builder = new StringBuilder(url);
        builder.append(url.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (!first) {
                builder.append('&');
            }
            first = false;
            builder.append(encode(entry.getKey()))
                    .append('=')
                    .append(encode(String.valueOf(entry.getValue())));
        }
        return builder.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String normalizedText(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String text(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim();
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value == null) return null;
        if ("true".equalsIgnoreCase(String.valueOf(value))) return true;
        if ("false".equalsIgnoreCase(String.valueOf(value))) return false;
        return null;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (Object item : iterable) {
            if (item != null) result.add(String.valueOf(item));
        }
        return result;
    }
}
