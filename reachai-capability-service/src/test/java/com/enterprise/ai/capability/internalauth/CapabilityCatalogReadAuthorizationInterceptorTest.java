package com.enterprise.ai.capability.internalauth;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetEntity;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetMapper;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CapabilityCatalogReadAuthorizationInterceptorTest {
    private static final String SECRET = "catalog-grant-test-secret-32bytes";
    private static final String RESOURCE = "/internal/capability/business-capabilities/projects/7/semantic-docs";
    private final ObjectMapper json = new ObjectMapper();
    private final ScanProjectMapper projects = mock(ScanProjectMapper.class);
    private final ToolDefinitionMapper tools = mock(ToolDefinitionMapper.class);
    private final BusinessMethodAssetMapper methods = mock(BusinessMethodAssetMapper.class);
    private final HttpApiAssetMapper apis = mock(HttpApiAssetMapper.class);
    private final AtomicInteger handlers = new AtomicInteger();

    @BeforeAll static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "catalog-read-test"), ScanProjectEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "catalog-tool-owner-test"), ToolDefinitionEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "catalog-method-owner-test"), BusinessMethodAssetEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "catalog-api-owner-test"), HttpApiAssetEntity.class);
    }

    @Test void signedForeignGrantIsDeniedBeforeTheBusinessHandler() throws Exception {
        owner(7L, "orders");
        var response = dispatch(request(RESOURCE, body(false, List.of("billing"), 7L)));
        assertEquals(403, response.getStatus());
        assertEquals(0, handlers.get());
        verify(projects).selectOne(any());
        assertNull(response.getHeader("X-EAF-Catalog-Project-Code"));
    }

    @Test void signedSessionGrantUsesActualOwnerAndReturnsEncodedIdentity() throws Exception {
        owner(7L, "orders + 中文");
        var response = dispatch(request(RESOURCE, body(false, List.of("orders + 中文"), 7L)));
        assertEquals(200, response.getStatus());
        assertEquals(1, handlers.get());
        assertEquals("PROJECT", response.getHeader("X-EAF-Catalog-Scope"));
        assertEquals("7", response.getHeader("X-EAF-Catalog-Project-Id"));
        assertEquals("orders%20%2B%20%E4%B8%AD%E6%96%87", response.getHeader("X-EAF-Catalog-Project-Code"));
    }

    @Test void unsignedScopeAndTamperedSignedBodyNeverReachOwnerOrHandler() throws Exception {
        var unsigned = new MockHttpServletRequest("GET", RESOURCE);
        unsigned.setContent(body(true, List.of(), 7L));
        assertEquals(401, dispatch(unsigned).getStatus());
        var tampered = request(RESOURCE, body(false, List.of("billing"), 7L));
        tampered.setContent(body(true, List.of(), 7L));
        assertEquals(401, dispatch(tampered).getStatus());
        assertEquals(0, handlers.get());
        verifyNoInteractions(projects);
    }

    @Test void queryProjectTamperingAndDuplicateScopeAreDeniedDespiteValidPathBodySignature() throws Exception {
        var altered = request("/internal/capability/business-methods", body(false, List.of("orders"), 7L));
        altered.setParameter("projectId", "8");
        assertEquals(403, dispatch(altered).getStatus());
        var duplicate = request("/internal/capability/http-apis", body(false, List.of("orders"), 7L));
        duplicate.setParameter("projectId", "7", "8");
        assertEquals(400, dispatch(duplicate).getStatus());
        assertEquals(0, handlers.get());
        verifyNoInteractions(projects);
    }

    @Test void projectListRequiresActualProjectGrantAndGlobalListRequiresGlobalGrant() throws Exception {
        owner(7L, "orders");
        var selected = request("/internal/capability/http-apis", body(false, List.of("orders"), 7L));
        selected.setParameter("projectId", "7");
        assertEquals("orders", dispatch(selected).getHeader("X-EAF-Catalog-Project-Code"));
        assertEquals(403, dispatch(request("/api/tools", body(false, List.of("orders"), null))).getStatus());
        var global = dispatch(request("/api/tools", body(true, List.of(), null)));
        assertEquals(200, global.getStatus());
        assertEquals("GLOBAL", global.getHeader("X-EAF-Catalog-Scope"));
        assertEquals(2, handlers.get());
        verify(projects, times(1)).selectOne(any());
    }

    @Test void absentGrantMalformedScopeAndForeignPathIdAreDeniedBeforeBusinessHandler() throws Exception {
        assertEquals(400, dispatch(request(RESOURCE, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))).getStatus());
        assertEquals(403, dispatch(request(RESOURCE, body(true, List.of(), 8L))).getStatus());
        assertEquals(0, handlers.get());
        verifyNoInteractions(projects);
    }

    @Test void existingWriteAuthorizationRemainsWithItsOriginalBoundary() throws Exception {
        var request = new MockHttpServletRequest("PUT", RESOURCE);
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        headers("PUT", RESOURCE, request.getContentAsByteArray()).forEach(request::addHeader);
        assertEquals(200, dispatch(request).getStatus());
        assertEquals(1, handlers.get());
        verifyNoInteractions(projects);
    }

    @Test void namedMethodResolvesMinimalActualOwnerBeforeAllowingItsHandler() throws Exception {
        owner(7L, "orders");
        var method = new BusinessMethodAssetEntity(); method.setId(11L); method.setProjectId(7L); method.setProjectCode("orders");
        when(methods.selectOne(any())).thenReturn(method);
        var response = dispatch(request("/internal/capability/business-methods/orders:lookup", body(false, List.of("orders"), null)));
        assertEquals(200, response.getStatus()); assertEquals(1, handlers.get());
        assertEquals("7", response.getHeader("X-EAF-Catalog-Project-Id"));
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BusinessMethodAssetEntity>> query =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(methods).selectOne(query.capture());
        assertFalse(query.getValue().getSqlSelect().contains("accepted_revision_id"));
        assertFalse(query.getValue().getSqlSelect().contains("description"));
        assertTrue(query.getValue().getSqlSegment().contains("accepted_revision_id"));
        verifyNoInteractions(tools, apis);
    }

    @Test void namedForeignMethodNeverRunsItsHandlerAndItsSignedPathCannotBeChanged() throws Exception {
        owner(7L, "orders");
        var method = new BusinessMethodAssetEntity(); method.setId(11L); method.setProjectId(7L); method.setProjectCode("orders");
        when(methods.selectOne(any())).thenReturn(method);
        assertEquals(403, dispatch(request("/internal/capability/business-methods/orders:lookup", body(false, List.of("billing"), null))).getStatus());
        var changed = request("/internal/capability/business-methods/orders:lookup", body(false, List.of("orders"), null));
        changed.setRequestURI("/internal/capability/business-methods/foreign");
        assertEquals(401, dispatch(changed).getStatus()); assertEquals(0, handlers.get());
        verify(methods, times(1)).selectOne(any());
    }

    @Test void httpIdentityChecksExactProjectCodeRatherThanDatabaseCollation() throws Exception {
        owner(7L, "orders");
        var api = new HttpApiAssetEntity(); api.setId(9L); api.setProjectId(7L); api.setProjectCode("Orders");
        when(apis.selectOne(any())).thenReturn(api);
        assertEquals(409, dispatch(request("/internal/capability/http-apis/9/identity", body(false, List.of("orders"), null))).getStatus());
        assertEquals(0, handlers.get());
        api.setProjectCode("orders");
        var allowed = dispatch(request("/internal/capability/http-apis/9", body(false, List.of("orders"), null)));
        assertEquals(200, allowed.getStatus()); assertEquals(1, handlers.get());
        assertEquals("orders", allowed.getHeader("X-EAF-Catalog-Project-Code"));
    }

    @Test void minimalToolIdentityUsesTheSameGlobalAndProjectGrantBoundary() throws Exception {
        var tool = new ToolDefinitionEntity(); tool.setId(5L);
        when(tools.selectOne(any())).thenReturn(tool);
        assertEquals(403, dispatch(request("/api/tools/platform_read/identity", body(false, List.of("orders"), null))).getStatus());
        var global = dispatch(request("/api/tools/platform_read/identity", body(true, List.of(), null)));
        assertEquals("GLOBAL", global.getHeader("X-EAF-Catalog-Scope")); assertEquals(1, handlers.get());
        verifyNoInteractions(projects, methods, apis);
    }

    @Test void everyDetailShapeRequiresTheSignedGrantBeforeAnyOwnerRead() throws Exception {
        for (String path : List.of("/api/tools/orders_read", "/api/tools/orders_read/identity",
                "/internal/capability/business-methods/orders:lookup", "/internal/capability/http-apis/9", "/internal/capability/http-apis/9/identity")) {
            assertEquals(400, dispatch(request(path, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))).getStatus(), path);
        }
        assertEquals(0, handlers.get()); verifyNoInteractions(projects, tools, methods, apis);
    }

    @Test void mvcNumericAliasesCannotBypassHmacOrScopeBeforeTheHandler() throws Exception {
        for (String id : List.of("+9", "%2B9", "-9", "%209", "9%20")) {
            for (String suffix : List.of("", "/identity")) {
                String path = "/internal/capability/http-apis/" + id + suffix;
                var unsigned = new MockHttpServletRequest("GET", path);
                assertEquals(401, dispatch(unsigned).getStatus(), path);
            }
        }
        var signedBadId = request("/internal/capability/http-apis/+9", body(false, List.of("orders"), null));
        assertEquals(400, dispatch(signedBadId).getStatus());
        assertEquals(0, handlers.get()); verifyNoInteractions(projects, tools, methods, apis);
    }

    private void owner(Long id, String code) {
        var project = new ScanProjectEntity(); project.setId(id); project.setProjectCode(code);
        when(projects.selectOne(any())).thenReturn(project);
    }

    private byte[] body(boolean global, List<String> codes, Long requested) throws Exception {
        Map<String, Object> grant = new LinkedHashMap<>();
        grant.put("global", global); grant.put("projects", codes); grant.put("requestedProjectId", requested);
        return json.writeValueAsBytes(Map.of("catalogReadGrant", grant));
    }

    private MockHttpServletRequest request(String path, byte[] body) {
        var request = new MockHttpServletRequest("GET", path); request.setContent(body);
        headers("GET", path, body).forEach(request::addHeader);
        return request;
    }

    private MockHttpServletResponse dispatch(MockHttpServletRequest request) throws Exception {
        var properties = new CapabilityInternalAuthProperties(SECRET, 300, 600, 10_000, 1_048_576);
        var verifier = new CapabilityInternalAuthVerifier(properties, (caller, nonce, now, ttl, max) -> true, json);
        var filter = new CapabilityInternalAuthFilter(verifier, properties);
        var interceptor = new CapabilityCatalogReadAuthorizationInterceptor(projects, tools, methods, apis, json);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (wrapped, result) -> {
            try {
                if (interceptor.preHandle((jakarta.servlet.http.HttpServletRequest) wrapped,
                        (jakarta.servlet.http.HttpServletResponse) result, new Object())) handlers.incrementAndGet();
            } catch (Exception exception) { throw new jakarta.servlet.ServletException(exception); }
        });
        return response;
    }

    private Map<String, String> headers(String method, String path, byte[] body) {
        String timestamp = String.valueOf(System.currentTimeMillis()); String nonce = UUID.randomUUID().toString();
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(method, path, InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "42", timestamp, nonce, digest);
        return Map.of(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                InternalServiceAuthHeaders.IDENTITY_USER_ID, "42", InternalServiceAuthHeaders.TIMESTAMP, timestamp,
                InternalServiceAuthHeaders.NONCE, nonce, InternalServiceAuthHeaders.BODY_SHA256, digest,
                InternalServiceAuthHeaders.SIGNATURE, InternalServiceHmac.sign(SECRET, canonical));
    }
}
