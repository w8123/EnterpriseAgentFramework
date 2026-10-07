package com.enterprise.ai.capability.catalog.businessmethod;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.capability.catalog.tool.CapabilityParameterContractCodec;
import com.enterprise.ai.capability.registry.CapabilityChangeLifecycle;
import com.enterprise.ai.capability.registry.CapabilitySourceStateEntity;
import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.net.URI;

/**
 * Owner-side, credential-safe execution snapshot for the Console trial-call flow.
 * This is intentionally separate from the broader catalog DTO: it contains only facts
 * Control needs to authorize and bind an invocation, never an endpoint URL or credential.
 */
@Service
@RequiredArgsConstructor
public class BusinessMethodInvocationContextService {

    private static final String BUSINESS_METHOD = "BUSINESS_METHOD";
    private final BusinessMethodCatalogService catalog;
    private final CapabilityChangeLifecycle changeLifecycle;
    private final RegistrySecurityService registrySecurityService;
    private final ObjectMapper objectMapper;

    public ConsoleCapabilityInvocationContracts.InvocationContext get(String name) {
        BusinessMethodDefinition method = catalog.find(name)
                .orElseThrow(() -> new BusinessMethodNotFoundException(name));
        BusinessMethodAssetEntity asset = method.asset();
        CapabilityRegistration declaration = method.declaration();
        String currentHash = method.revision().getInvocationHash();
        CapabilitySourceStateEntity state = changeLifecycle.sourceState(asset.getQualifiedName());
        String sourceHash = state == null ? null : trim(state.getSourceContractHash());
        String acceptedHash = state == null ? null : trim(state.getAcceptedContractHash());
        String sourceAvailability = method.sourceAvailability();
        boolean enabled = Boolean.TRUE.equals(asset.getEnabled());
        RegistryCredentialEntity credential = registrySecurityService.findPrimaryActiveCredential(asset.getProjectCode()).orElse(null);
        String executionRevision = BusinessMethodExecutionRevision.of(method, credential);
        boolean credentialAvailable = executionRevision != null;
        boolean businessIdentityRequired = ConsoleBusinessMethodDeclaration.hasRequiredRoles(declaration.metadata(), objectMapper);

        String code = null;
        String message = null;
        if (!enabled) {
            code = "CAPABILITY_DISABLED";
            message = "业务方法当前未启用";
        } else if (!StringUtils.hasText(asset.getProjectCode()) || asset.getProjectId() == null
                || !StringUtils.hasText(asset.getQualifiedName())
                || !"ACCEPTED".equals(asset.getStatus())) {
            code = "CAPABILITY_SOURCE_UNAVAILABLE";
            message = "业务方法缺少可验证的项目来源绑定";
        } else if (!currentHash.equals(sourceHash) || !currentHash.equals(acceptedHash)
                || !"READY".equals(sourceAvailability)) {
            code = "CAPABILITY_CONTRACT_NOT_ACCEPTED";
            message = "业务方法当前契约与已接受来源不一致";
        } else if (!credentialAvailable) {
            code = "CAPABILITY_PROJECT_CREDENTIAL_REQUIRED";
            message = "项目签名凭证不可用";
        } else if (businessIdentityRequired) {
            code = "CAPABILITY_BUSINESS_IDENTITY_REQUIRED";
            message = "该业务方法要求业务用户身份，控制台试调用不会转发平台身份";
        }

        return new ConsoleCapabilityInvocationContracts.InvocationContext(
                ConsoleCapabilityInvocationContracts.CONTRACT_VERSION,
                asset.getInvocationName(),
                asset.getQualifiedName(),
                asset.getQualifiedName(),
                BUSINESS_METHOD,
                asset.getProjectId(),
                trim(asset.getProjectCode()),
                currentHash,
                acceptedHash,
                sourceHash,
                sourceAvailability,
                enabled,
                trim(declaration.sideEffect()),
                safeParameters(declaration),
                trim(declaration.requestBodyType()),
                trim(declaration.responseType()),
                targetDescription(declaration),
                null,
                "UNKNOWN",
                credentialAvailable,
                businessIdentityRequired,
                code == null,
                code,
                message,
                ConsoleBusinessMethodDeclaration.timeoutMillis(declaration.metadata(), objectMapper), executionRevision);
    }

    private List<ConsoleCapabilityInvocationContracts.Parameter> safeParameters(CapabilityRegistration declaration) {
        return CapabilityParameterContractCodec.logicalTree(declaration.parameters()).stream()
                .map(parameter -> safeParameter(parameter, false)).toList();
    }

    private ConsoleCapabilityInvocationContracts.Parameter safeParameter(ToolDefinitionParameter parameter,
                                                                         boolean inheritedSensitive) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        boolean sensitive = inheritedSensitive || sensitive(parameter.metadata());
        if (sensitive) {
            metadata.put("sensitive", true);
        } else if (parameter.metadata() instanceof Map<?, ?> source) {
            for (String key : List.of("default", "defaultValue", "example", "examples", "enum", "const", "format",
                    "pattern", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
                    "minLength", "maxLength", "minItems", "maxItems", "dictType", "itemsType", "openObject")) {
                if (source.containsKey(key)) metadata.put(key, source.get(key));
            }
        }
        List<ConsoleCapabilityInvocationContracts.Parameter> children = parameter.children() == null
                ? List.of() : parameter.children().stream().map(child -> safeParameter(child, sensitive)).toList();
        return new ConsoleCapabilityInvocationContracts.Parameter(
                trim(parameter.name()), trim(parameter.type()), trim(parameter.description()), parameter.required(),
                trim(parameter.location()), children, metadata);
    }

    private boolean sensitive(Object metadata) {
        if (metadata instanceof Map<?, ?> values) {
            Object value = values.get("sensitive");
            return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
        }
        return false;
    }

    private String targetDescription(CapabilityRegistration declaration) {
        String method = StringUtils.hasText(declaration.httpMethod()) ? declaration.httpMethod().trim().toUpperCase() : "POST";
        try {
            if (!StringUtils.hasText(declaration.baseUrl())) return method + " · 已接受项目契约";
            URI uri = URI.create(declaration.baseUrl().trim());
            if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                return method + " · 已接受项目契约";
            }
            String authority = uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
            String path = joinPath(uri.getRawPath(), declaration.contextPath(), declaration.endpointPath());
            return method + " · " + uri.getScheme().toLowerCase() + "://" + authority + path;
        } catch (IllegalArgumentException invalid) {
            return method + " · 已接受项目契约";
        }
    }

    private String joinPath(String basePath, String... additions) {
        StringBuilder path = new StringBuilder();
        if (StringUtils.hasText(basePath)) path.append('/').append(stripSlashes(basePath));
        if (additions != null) {
            for (String addition : additions) {
                String safePath = pathOnly(addition);
                if (StringUtils.hasText(safePath)) path.append('/').append(stripSlashes(safePath));
            }
        }
        return path.isEmpty() ? "/" : path.toString().replaceAll("/{2,}", "/");
    }

    private String pathOnly(String value) {
        if (!StringUtils.hasText(value)) return "";
        String trimmed = value.trim();
        int query = trimmed.indexOf('?');
        int fragment = trimmed.indexOf('#');
        int end = query < 0 ? (fragment < 0 ? trimmed.length() : fragment)
                : (fragment < 0 ? query : Math.min(query, fragment));
        return trimmed.substring(0, end);
    }

    private String stripSlashes(String value) { return value.replaceAll("^/+|/+$", ""); }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public static class BusinessMethodNotFoundException extends RuntimeException {
        public BusinessMethodNotFoundException(String name) {
            super("Business method not found: " + name);
        }
    }
}
