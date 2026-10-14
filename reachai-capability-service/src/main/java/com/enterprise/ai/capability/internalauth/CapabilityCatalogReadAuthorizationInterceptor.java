package com.enterprise.ai.capability.internalauth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetEntity;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetMapper;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/** Enforces the signed platform scope against the actual owner before catalog data is read. */
@Component
public class CapabilityCatalogReadAuthorizationInterceptor implements HandlerInterceptor {
    private static final Pattern PROJECT_RESOURCE = Pattern.compile(
            "/internal/capability/(?:business-capabilities|api-graph)/projects/([^/]+)(?:/.*)?");
    private final ScanProjectMapper projects;
    private final ToolDefinitionMapper tools;
    private final BusinessMethodAssetMapper methods;
    private final HttpApiAssetMapper apis;
    private final ObjectMapper json;
    private static final Pattern TOOL_DETAIL = Pattern.compile("/api/tools/([^/]+)(?:/identity)?");
    private static final Pattern METHOD_DETAIL = Pattern.compile("/internal/capability/business-methods/([^/]+)");
    private static final Pattern API_DETAIL = Pattern.compile("/internal/capability/http-apis/([^/]+)(?:/identity)?");

    public CapabilityCatalogReadAuthorizationInterceptor(ScanProjectMapper projects, ToolDefinitionMapper tools,
            BusinessMethodAssetMapper methods, HttpApiAssetMapper apis, ObjectMapper json) {
        this.projects = projects;
        this.tools = tools;
        this.methods = methods;
        this.apis = apis;
        this.json = json;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!"GET".equals(request.getMethod())) return true;
        String path = CapabilityInternalAuthFilter.normalizePath(request);
        var resource = PROJECT_RESOURCE.matcher(path);
        boolean detail = TOOL_DETAIL.matcher(path).matches() || METHOD_DETAIL.matcher(path).matches() || API_DETAIL.matcher(path).matches();
        boolean list = "/internal/capability/business-methods".equals(path)
                || "/internal/capability/http-apis".equals(path) || "/api/tools".equals(path);
        if (!resource.matches() && !list && !detail) return true;
        Object identity = request.getAttribute(CapabilityVerifiedInternalServiceAuth.REQUEST_ATTR);
        if (!(identity instanceof CapabilityVerifiedInternalServiceAuth verified)
                || !InternalServiceAuthHeaders.CALLER_CONTROL.equals(verified.caller())
                || !InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION.equals(verified.identitySource())
                || verified.identityUserId() == null || verified.identityUserId().isBlank()) {
            return reject(response, 401, "CATALOG_READ_CALLER_INVALID");
        }
        JsonNode body;
        try {
            body = json.readTree(request.getInputStream());
        } catch (Exception malformed) {
            return reject(response, 400, "CATALOG_READ_GRANT_INVALID");
        }
        JsonNode grant = body == null ? null : body.get("catalogReadGrant");
        if (body == null || !body.isObject() || body.size() != 1 || grant == null || !grant.isObject()
                || grant.size() != 3 || !grant.path("global").isBoolean() || !grant.path("projects").isArray()
                || !grant.has("requestedProjectId")) return reject(response, 400, "CATALOG_READ_GRANT_INVALID");
        for (JsonNode project : grant.path("projects")) {
            if (!project.isTextual() || project.asText().isBlank()) return reject(response, 400, "CATALOG_READ_GRANT_INVALID");
        }
        Long requested;
        CatalogOwner owner = null;
        try {
            if (detail) {
                owner = detailOwner(path);
                if (owner == null) return reject(response, 404, "CATALOG_OWNER_NOT_FOUND");
                requested = owner.projectId();
                if (requested != null && requested <= 0 || requested == null && owner.projectCode() != null) {
                    return reject(response, 409, "CATALOG_OWNER_PROJECT_INVALID");
                }
            } else requested = resource.matches() ? positiveId(resource.group(1)) : queryProjectId(request);
        } catch (IllegalArgumentException invalid) {
            return reject(response, 400, "CATALOG_READ_SCOPE_INVALID");
        }
        JsonNode declared = grant.get("requestedProjectId");
        if (!(detail && declared.isNull()) && (requested == null ? !declared.isNull()
                : !declared.isIntegralNumber() || !declared.canConvertToLong() || declared.longValue() != requested)) {
            return reject(response, 403, "CATALOG_READ_SCOPE_MISMATCH");
        }
        boolean global = grant.path("global").booleanValue();
        if (requested == null) {
            if (!global) return reject(response, 403, "CATALOG_READ_SCOPE_DENIED");
            response.setHeader("X-EAF-Catalog-Scope", "GLOBAL");
            return true;
        }
        ScanProjectEntity project = projects.selectOne(Wrappers.<ScanProjectEntity>lambdaQuery()
                .select(ScanProjectEntity::getId, ScanProjectEntity::getProjectCode)
                .eq(ScanProjectEntity::getId, requested));
        if (project == null || !Objects.equals(requested, project.getId())
                || project.getProjectCode() == null || project.getProjectCode().isBlank()) {
            return reject(response, 404, "CATALOG_PROJECT_NOT_FOUND");
        }
        String code = project.getProjectCode().trim();
        if (detail && !Objects.equals(owner.projectCode(), project.getProjectCode())) {
            return reject(response, 409, "CATALOG_OWNER_PROJECT_MISMATCH");
        }
        boolean permitted = global;
        for (JsonNode allowed : grant.path("projects")) permitted |= code.equals(allowed.asText().trim());
        if (!permitted) return reject(response, 403, "CATALOG_READ_SCOPE_DENIED");
        response.setHeader("X-EAF-Catalog-Scope", "PROJECT");
        response.setHeader("X-EAF-Catalog-Project-Id", String.valueOf(requested));
        response.setHeader("X-EAF-Catalog-Project-Code", URLEncoder.encode(code, StandardCharsets.UTF_8).replace("+", "%20"));
        return true;
    }

    /** Minimal owner lookup only; no contract parsing, source hydration or availability work before authorization. */
    private CatalogOwner detailOwner(String path) {
        var tool = TOOL_DETAIL.matcher(path);
        if (tool.matches()) {
            ToolDefinitionEntity row = tools.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                    .select(ToolDefinitionEntity::getId, ToolDefinitionEntity::getProjectId, ToolDefinitionEntity::getProjectCode)
                    .eq(ToolDefinitionEntity::getName, tool.group(1))
                    .in(ToolDefinitionEntity::getAssetType, java.util.List.of("BUSINESS_METHOD", "HTTP_API")).last("limit 1"));
            return row == null ? null : new CatalogOwner(row.getProjectId(), row.getProjectCode());
        }
        var method = METHOD_DETAIL.matcher(path);
        if (method.matches()) {
            BusinessMethodAssetEntity row = methods.selectOne(Wrappers.<BusinessMethodAssetEntity>lambdaQuery()
                    .select(BusinessMethodAssetEntity::getId, BusinessMethodAssetEntity::getProjectId, BusinessMethodAssetEntity::getProjectCode)
                    .isNotNull(BusinessMethodAssetEntity::getAcceptedRevisionId).ne(BusinessMethodAssetEntity::getStatus, "UNACCEPTED")
                    .and(query -> query.eq(BusinessMethodAssetEntity::getQualifiedName, method.group(1))
                            .or().eq(BusinessMethodAssetEntity::getInvocationName, method.group(1))));
            return row == null ? null : new CatalogOwner(row.getProjectId(), row.getProjectCode());
        }
        var api = API_DETAIL.matcher(path);
        if (api.matches()) {
            HttpApiAssetEntity row = apis.selectOne(Wrappers.<HttpApiAssetEntity>lambdaQuery()
                    .select(HttpApiAssetEntity::getId, HttpApiAssetEntity::getProjectId, HttpApiAssetEntity::getProjectCode)
                    .eq(HttpApiAssetEntity::getId, positiveId(api.group(1))));
            return row == null ? null : new CatalogOwner(row.getProjectId(), row.getProjectCode());
        }
        return null;
    }

    private record CatalogOwner(Long projectId, String projectCode) { }

    private static Long queryProjectId(HttpServletRequest request) {
        String[] values = request.getParameterValues("projectId");
        if (values == null) return null;
        if (values.length != 1) throw new IllegalArgumentException("projectId is ambiguous");
        return positiveId(values[0]);
    }

    private static Long positiveId(String value) {
        if (value == null || !value.matches("[0-9]+")) throw new IllegalArgumentException("projectId is invalid");
        Long id = Long.valueOf(value);
        if (id <= 0) throw new IllegalArgumentException("projectId must be positive");
        return id;
    }

    private boolean reject(HttpServletResponse response, int status, String code) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), java.util.Map.of("code", code));
        return false;
    }
}
