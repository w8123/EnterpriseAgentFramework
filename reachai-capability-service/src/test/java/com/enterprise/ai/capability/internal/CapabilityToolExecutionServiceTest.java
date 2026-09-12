package com.enterprise.ai.capability.internal;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationClaims;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationToken;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityToolExecutionServiceTest {

    @Test
    void executesEnabledHttpToolThroughInvoker() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapturingInvoker invoker = new CapturingInvoker(Map.of("statusCode", 200, "body", Map.of("orderStatus", "PAID")));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker, null, mock(CapabilitySourceContractGuard.class));
        when(mapper.selectOne(any())).thenReturn(tool("orders:queryOrder", true));

        Map<String, Object> response = service.execute("orders:queryOrder",
                Map.of("input", Map.of("orderNo", "A001")));

        assertEquals(true, response.get("success"));
        assertEquals("orders:queryOrder", response.get("qualifiedName"));
        assertEquals("查询订单", response.get("toolTitle"));
        assertEquals(Map.of("orderStatus", "PAID"), response.get("data"));
        assertEquals("POST", invoker.invocation.method());
        assertEquals("http://orders/api/orders/query", invoker.invocation.url());
        assertEquals(Map.of("orderNo", "A001"), invoker.invocation.body());
    }

    @Test
    void forwardsInvocationControlsToTheOutboundTransport() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapturingInvoker invoker = new CapturingInvoker(
                Map.of("statusCode", 200, "body", Map.of("ok", true)));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker, null, mock(CapabilitySourceContractGuard.class));
        when(mapper.selectOne(any())).thenReturn(tool("orders:queryOrder", true));
        Map<String, Object> traceContext = Map.of(
                "traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");

        service.execute("orders:queryOrder", Map.of(
                "input", Map.of("orderNo", "A001"),
                "invocationId", "inv-7",
                "deadlineEpochMs", 123456789L,
                "idempotencyKey", "wf:inv-7",
                "traceContext", traceContext));

        assertEquals("inv-7", invoker.invocation.metadata().get("invocationId"));
        assertEquals(123456789L, invoker.invocation.metadata().get("deadlineEpochMs"));
        assertEquals("wf:inv-7", invoker.invocation.metadata().get("idempotencyKey"));
        assertEquals(traceContext, invoker.invocation.metadata().get("traceContext"));
    }

    @Test
    void marksNon200BusinessResponseAsCapabilityFailure() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapturingInvoker invoker = new CapturingInvoker(Map.of(
                "statusCode", 200,
                "body", Map.of("code", "500", "success", false, "message", "无权查看班组")));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker, null, mock(CapabilitySourceContractGuard.class));
        when(mapper.selectOne(any())).thenReturn(tool("qmssmp:team.search", true));

        Map<String, Object> response = service.execute("qmssmp:team.search", Map.of("input", Map.of()));

        assertEquals(false, response.get("success"));
        assertEquals("CAPABILITY_BUSINESS_RESPONSE_FAILED", response.get("code"));
        assertEquals("500", response.get("businessCode"));
        assertEquals("查询失败：无权查看班组", response.get("message"));
    }

    @Test
    void keepsStringBusinessCode200Successful() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        Map<String, Object> businessBody = Map.of("code", "200", "success", true, "data", Map.of("total", 0));
        CapturingInvoker invoker = new CapturingInvoker(Map.of("statusCode", 200, "body", businessBody));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker, null, mock(CapabilitySourceContractGuard.class));
        when(mapper.selectOne(any())).thenReturn(tool("qmssmp:team.search", true));

        Map<String, Object> response = service.execute("qmssmp:team.search", Map.of("input", Map.of()));

        assertEquals(true, response.get("success"));
        assertEquals(businessBody, response.get("data"));
    }

    @Test
    void rejectsDisabledTool() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(
                mapper,
                invocation -> Map.of(), null, mock(CapabilitySourceContractGuard.class));
        when(mapper.selectOne(any())).thenReturn(tool("orders:queryOrder", false));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.execute("orders:queryOrder", Map.of()));

        assertEquals("Tool definition is disabled: orders:queryOrder", ex.getMessage());
    }

    @Test
    void signedEvalPolicyRejectsWriteOrUndeclaredCapabilityBeforeInvocation() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapturingInvoker invoker = new CapturingInvoker(Map.of("statusCode", 200, "body", Map.of()));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker, null, mock(CapabilitySourceContractGuard.class));
        ToolDefinitionEntity tool = tool("orders:updateOrder", true);
        tool.setSideEffect("WRITE");
        when(mapper.selectOne(any())).thenReturn(tool);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.execute("orders:updateOrder", Map.of(
                        "input", Map.of("orderNo", "A001"),
                        "evaluationPolicy", Map.of(
                                "mode", "READ_ONLY_EXECUTION",
                                "sideEffectPolicy", "READ_ONLY_ONLY"))));

        assertTrue(ex.getMessage().startsWith("EVAL_SIDE_EFFECT_BLOCKED"));
        assertNull(invoker.invocation);
    }

    @Test
    void signedEvalPolicyAllowsExplicitReadOnlyCapability() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapturingInvoker invoker = new CapturingInvoker(
                Map.of("statusCode", 200, "body", Map.of("orderStatus", "PAID")));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker, null, mock(CapabilitySourceContractGuard.class));
        ToolDefinitionEntity tool = tool("orders:queryOrder", true);
        tool.setSideEffect("READ_ONLY");
        when(mapper.selectOne(any())).thenReturn(tool);

        Map<String, Object> response = service.execute("orders:queryOrder", Map.of(
                "input", Map.of("orderNo", "A001"),
                "evaluationPolicy", Map.of(
                        "mode", "READ_ONLY_EXECUTION",
                        "sideEffectPolicy", "READ_ONLY_ONLY")));

        assertEquals(true, response.get("success"));
        assertEquals("orders:queryOrder", response.get("qualifiedName"));
        assertEquals("POST", invoker.invocation.method());
    }

    @Test
    void rejectsResolverWhenExactToolOrProjectConstraintDoesNotMatch() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapturingInvoker invoker = new CapturingInvoker(Map.of("statusCode", 200, "body", Map.of()));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker, null, mock(CapabilitySourceContractGuard.class));
        ToolDefinitionEntity tool = tool("mall:order.resolve", true);
        tool.setProjectCode("mall");
        when(mapper.selectOne(any())).thenReturn(tool);

        IllegalStateException projectMismatch = assertThrows(IllegalStateException.class,
                () -> service.execute("mall:order.resolve", Map.of(
                        "input", Map.of(),
                        "constraints", Map.of(
                                "expectedQualifiedName", "mall:order.resolve",
                                "expectedProjectCode", "crm"))));
        assertEquals("Tool does not belong to the required project", projectMismatch.getMessage());
        assertNull(invoker.invocation);

        IllegalStateException nameMismatch = assertThrows(IllegalStateException.class,
                () -> service.execute("mall:order.resolve", Map.of(
                        "input", Map.of(),
                        "constraints", Map.of(
                                "expectedQualifiedName", "mall:customer.resolve",
                                "expectedProjectCode", "mall"))));
        assertEquals("Tool does not match the required qualified name", nameMismatch.getMessage());
        assertNull(invoker.invocation);
    }

    @Test
    void rejectsResolverWithoutCurrentUserOrSignedBusinessIdentity() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapturingInvoker invoker = new CapturingInvoker(Map.of("statusCode", 200, "body", Map.of()));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker, null, mock(CapabilitySourceContractGuard.class));
        ToolDefinitionEntity tool = tool("mall:order.resolve", true);
        tool.setProjectCode("mall");
        when(mapper.selectOne(any())).thenReturn(tool);
        Map<String, Object> constraints = Map.of(
                "expectedQualifiedName", "mall:order.resolve",
                "expectedProjectCode", "mall",
                "requireUserIdentity", true,
                "requireSignedInvocation", true);

        IllegalStateException missingUser = assertThrows(IllegalStateException.class,
                () -> service.execute("mall:order.resolve", Map.of(
                        "input", Map.of(), "context", Map.of(), "constraints", constraints)));
        assertEquals("Tool invocation requires a current user identity", missingUser.getMessage());

        IllegalStateException unsigned = assertThrows(IllegalStateException.class,
                () -> service.execute("mall:order.resolve", Map.of(
                        "input", Map.of(),
                        "context", Map.of("externalUserId", "user-1"),
                        "constraints", constraints)));
        assertEquals("Tool invocation requires a signed business identity", unsigned.getMessage());
        assertNull(invoker.invocation);
    }

    @Test
    void appendsInputAsQueryStringForGetTool() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapturingInvoker invoker = new CapturingInvoker(Map.of("statusCode", 200, "body", Map.of("orderStatus", "PAID")));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker, null, mock(CapabilitySourceContractGuard.class));
        ToolDefinitionEntity tool = tool("orders:queryOrder", true);
        tool.setHttpMethod("GET");
        when(mapper.selectOne(any())).thenReturn(tool);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("orderNo", "A001");
        input.put("verbose", true);
        service.execute("orders:queryOrder", Map.of("input", input));

        assertEquals("GET", invoker.invocation.method());
        assertEquals("http://orders/api/orders/query?orderNo=A001&verbose=true", invoker.invocation.url());
        assertEquals(Map.of(), invoker.invocation.body());
    }

    @Test
    void executesScanProjectHttpToolThroughInvoker() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapturingInvoker invoker = new CapturingInvoker(Map.of("statusCode", 200, "body", Map.of("orderStatus", "PAID")));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker, null, mock(CapabilitySourceContractGuard.class));
        ScanProjectToolEntity tool = scanTool(11L, true);

        Map<String, Object> response = service.execute(tool, Map.of("input", Map.of("orderNo", "A001")));

        assertEquals(true, response.get("success"));
        assertEquals(11L, response.get("scanToolId"));
        assertEquals("orders_create", response.get("toolName"));
        assertEquals("创建订单", response.get("toolTitle"));
        assertEquals(Map.of("orderStatus", "PAID"), response.get("data"));
        assertEquals("POST", invoker.invocation.method());
        assertEquals("http://orders/api/orders/create", invoker.invocation.url());
        assertEquals(Map.of("orderNo", "A001"), invoker.invocation.body());
    }

    @Test
    void rejectsUnlinkedSdkScanToolInsteadOfInvokingItUnsigned() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapturingInvoker invoker = new CapturingInvoker(Map.of("statusCode", 200, "body", Map.of("ok", true)));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker, null, mock(CapabilitySourceContractGuard.class));
        ScanProjectToolEntity scanTool = scanTool(11L, true);
        scanTool.setProjectId(33L);
        scanTool.setSourceLocation("sdk:mall:mall.order.create");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.execute(scanTool, Map.of("input", Map.of())));

        assertTrue(ex.getMessage().contains("signed Tool catalog"));
        assertNull(invoker.invocation);
    }

    @Test
    void signsLinkedScanProjectToolInvocation() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        RegistrySecurityService securityService = mock(RegistrySecurityService.class);
        CapturingInvoker invoker = new CapturingInvoker(Map.of("statusCode", 200, "body", Map.of("ok", true)));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker, securityService, mock(CapabilitySourceContractGuard.class));
        ScanProjectToolEntity scanTool = scanTool(11L, true);
        scanTool.setProjectId(33L);
        scanTool.setSourceLocation("sdk:mall:mall.order.query");
        scanTool.setGlobalToolDefinitionId(99L);
        ToolDefinitionEntity linkedTool = tool("mall:mall.order.query", true);
        linkedTool.setId(99L);
        linkedTool.setProjectId(33L);
        linkedTool.setProjectCode("mall");
        linkedTool.setName("orders_create");
        linkedTool.setSourceLocation("sdk:mall:mall.order.query");
        linkedTool.setEndpointPath("/orders/create");
        when(mapper.selectById(99L)).thenReturn(linkedTool);
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("mall");
        credential.setAppKey("mall");
        credential.setAppSecret("secret");
        when(securityService.findPrimaryActiveCredential("mall")).thenReturn(Optional.of(credential));

        service.execute(scanTool, Map.of("input", Map.of(), "context", Map.of("sessionId", "s-1")));

        Map<?, ?> headers = (Map<?, ?>) invoker.invocation.metadata().get("headers");
        String token = String.valueOf(headers.get(ReachAiInvocationToken.HEADER_NAME));
        ReachAiInvocationClaims claims = ReachAiInvocationToken.verify(
                "secret", token, "mall", "mall.order.query", System.currentTimeMillis());
        assertEquals("mall.order.query", claims.getCapabilityName());
        assertEquals("s-1", claims.getSessionId());
    }

    @Test
    void rejectsSignedScanInvocationWhenLinkedToolIsOutOfSync() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        CapturingInvoker invoker = new CapturingInvoker(Map.of("statusCode", 200, "body", Map.of("ok", true)));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker, null, mock(CapabilitySourceContractGuard.class));
        ScanProjectToolEntity scanTool = scanTool(11L, true);
        scanTool.setProjectId(33L);
        scanTool.setSourceLocation("sdk:mall:mall.order.create");
        scanTool.setGlobalToolDefinitionId(99L);
        ToolDefinitionEntity linkedTool = tool("mall:mall.order.create", true);
        linkedTool.setId(99L);
        linkedTool.setProjectId(33L);
        linkedTool.setName("orders_create");
        linkedTool.setSourceLocation("sdk:mall:mall.order.create");
        when(mapper.selectById(99L)).thenReturn(linkedTool);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.execute(scanTool, Map.of("input", Map.of())));

        assertEquals(true, ex.getMessage().contains("endpoint"));
        assertNull(invoker.invocation);
    }

    @Test
    void signsSdkInvocationWithRuntimePrincipal() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        RegistrySecurityService securityService = mock(RegistrySecurityService.class);
        CapturingInvoker invoker = new CapturingInvoker(Map.of("statusCode", 200, "body", Map.of("ok", true)));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker, securityService, mock(CapabilitySourceContractGuard.class));
        ToolDefinitionEntity tool = tool("qmssmp:teamArchivePage", true);
        tool.setProjectCode("qmssmp");
        when(mapper.selectOne(any())).thenReturn(tool);
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("qmssmp");
        credential.setAppKey("qmssmp");
        credential.setAppSecret("secret");
        when(securityService.findPrimaryActiveCredential("qmssmp")).thenReturn(Optional.of(credential));

        service.execute("qmssmp:teamArchivePage", Map.of(
                "input", Map.of("pageIndex", 1),
                "context", Map.of(
                        "externalUserId", "u-1",
                        "userName", "Alice",
                        "deptId", "dept-1",
                        "deptName", "Operations",
                        "roles", java.util.List.of("team-reader"),
                        "attributes", Map.of("region", "east"),
                        "sessionId", "s-1")));

        Map<?, ?> headers = (Map<?, ?>) invoker.invocation.metadata().get("headers");
        String token = String.valueOf(headers.get(ReachAiInvocationToken.HEADER_NAME));
        ReachAiInvocationClaims claims = ReachAiInvocationToken.verify(
                "secret", token, "qmssmp", "queryOrder", System.currentTimeMillis());
        assertEquals("u-1", claims.getExternalUserId());
        assertEquals("Alice", claims.getUserName());
        assertEquals("dept-1", claims.getDeptId());
        assertEquals("Operations", claims.getDeptName());
        assertEquals(java.util.List.of("team-reader"), claims.getRoles());
        assertEquals(Map.of("region", "east"), claims.getAttributes());
        assertEquals("s-1", claims.getSessionId());
    }

    @Test
    void signsSdkInvocationWithDeclaredCapabilityNameInsteadOfStorageName() {
        ToolDefinitionMapper mapper = mock(ToolDefinitionMapper.class);
        RegistrySecurityService securityService = mock(RegistrySecurityService.class);
        CapturingInvoker invoker = new CapturingInvoker(Map.of("statusCode", 200, "body", Map.of("ok", true)));
        CapabilityToolExecutionService service = new CapabilityToolExecutionService(mapper, invoker, securityService, mock(CapabilitySourceContractGuard.class));
        ToolDefinitionEntity tool = tool("bzjs10:qmssmp.team.search", true);
        tool.setName("bzjs10_qmssmp_team_search");
        tool.setProjectCode("bzjs10");
        tool.setSourceLocation("sdk:bzjs10:qmssmp.team.search");
        when(mapper.selectOne(any())).thenReturn(tool);
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setProjectCode("bzjs10");
        credential.setAppKey("bzjs10");
        credential.setAppSecret("secret");
        when(securityService.findPrimaryActiveCredential("bzjs10")).thenReturn(Optional.of(credential));

        service.execute("bzjs10:qmssmp.team.search", Map.of("input", Map.of("managerName", "张三")));

        Map<?, ?> headers = (Map<?, ?>) invoker.invocation.metadata().get("headers");
        String token = String.valueOf(headers.get(ReachAiInvocationToken.HEADER_NAME));
        ReachAiInvocationClaims claims = ReachAiInvocationToken.verify(
                "secret", token, "bzjs10", "qmssmp.team.search", System.currentTimeMillis());
        assertEquals("qmssmp.team.search", claims.getCapabilityName());
    }

    private ToolDefinitionEntity tool(String qualifiedName, boolean enabled) {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setId(9L);
        entity.setName("queryOrder");
        entity.setTitle("查询订单");
        entity.setQualifiedName(qualifiedName);
        entity.setEnabled(enabled);
        entity.setHttpMethod("POST");
        entity.setBaseUrl("http://orders");
        entity.setContextPath("/api");
        entity.setEndpointPath("/orders/query");
        entity.setRequestBodyType("json");
        entity.setResponseType("json");
        return entity;
    }

    private ScanProjectToolEntity scanTool(Long id, boolean enabled) {
        ScanProjectToolEntity entity = new ScanProjectToolEntity();
        entity.setId(id);
        entity.setName("orders_create");
        entity.setTitle("创建订单");
        entity.setEnabled(enabled);
        entity.setHttpMethod("POST");
        entity.setBaseUrl("http://orders");
        entity.setContextPath("/api");
        entity.setEndpointPath("/orders/create");
        entity.setRequestBodyType("json");
        entity.setResponseType("json");
        return entity;
    }

    private static final class CapturingInvoker implements CapabilityHttpToolInvoker {
        private final Map<String, Object> response;
        private CapabilityHttpToolInvocation invocation;

        private CapturingInvoker(Map<String, Object> response) {
            this.response = response;
        }

        @Override
        public Map<String, Object> invoke(CapabilityHttpToolInvocation invocation) {
            this.invocation = invocation;
            return response;
        }
    }
}
