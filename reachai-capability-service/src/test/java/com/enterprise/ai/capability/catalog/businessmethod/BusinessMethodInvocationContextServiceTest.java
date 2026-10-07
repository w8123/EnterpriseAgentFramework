package com.enterprise.ai.capability.catalog.businessmethod;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.capability.registry.CapabilityChangeLifecycle;
import com.enterprise.ai.capability.registry.CapabilitySourceStateEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessMethodInvocationContextServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void emptyRequiredRolesRemainExecutableThroughTheOwnerController() {
        var context = context(Map.of("requiredRoles", List.of()), List.of(), null, "READY");
        assertFalse(context.businessIdentityRequired()); assertTrue(context.executable());
        assertEquals("orders:lookup", context.qualifiedName()); assertEquals("orders_lookup", context.name());
    }
    @Test void roleInterpretationIsNarrowAndFailClosedForMalformedMetadata() {
        assertFalse(ConsoleBusinessMethodDeclaration.hasRequiredRoles((Object) null, json));
        assertFalse(ConsoleBusinessMethodDeclaration.hasRequiredRoles(Map.of("requiredRoles", List.of()), json));
        assertFalse(ConsoleBusinessMethodDeclaration.hasRequiredRoles(Map.of("requiredRoles", List.of("", "  ")), json));
        assertTrue(ConsoleBusinessMethodDeclaration.hasRequiredRoles(Map.of("requiredRoles", List.of("order-reader")), json));
        assertTrue(ConsoleBusinessMethodDeclaration.hasRequiredRoles("{not-json", json));
        assertFalse(context(Map.of("requiredRoles", List.of("order-reader")), List.of(), null, "READY").executable());
    }
    @Test void parameterExamplesRemainUsefulAndTargetDescriptionDropsAddressSecrets() {
        var normal = new ToolDefinitionParameter("request", "object", "request", true, null, List.of(),
                Map.of("example", Map.of("quantity", 2), "enum", List.of("standard"), "minimum", 1));
        var sensitive = new ToolDefinitionParameter("password", "string", "secret", false, null, List.of(),
                Map.of("sensitive", true, "default", "private", "example", "private"));
        var context = context(Map.of("requiredRoles", List.of(), "timeoutMs", 120000), List.of(normal, sensitive),
                "https://user:private@example.test:8443/api?access_token=private", "READY");
        assertEquals(60_000L, context.timeoutMs());
        assertEquals("POST · https://example.test:8443/api/v1/orders", context.targetDescription());
        assertFalse(context.targetDescription().contains("private"));
        assertEquals(normal.metadata(), context.parameters().get(0).metadata());
        assertEquals(Map.of("sensitive", true), context.parameters().get(1).metadata());
    }
    @Test void sensitiveParentRedactsNestedExamplesAndSourceDriftPreventsDispatch() {
        var nested = new ToolDefinitionParameter("phone", "string", "phone", false, null, List.of(),
                Map.of("example", "sensitive-example", "default", "sensitive-default"));
        var request = new ToolDefinitionParameter("request", "object", "request", true, null, List.of(nested),
                Map.of("sensitive", true, "example", Map.of("phone", "sensitive-example")));
        var context = context(Map.of("requiredRoles", List.of()), List.of(request), null, "CONTRACT_DRIFT");
        assertFalse(context.executable()); assertEquals("CAPABILITY_CONTRACT_NOT_ACCEPTED", context.blockingCode());
        assertEquals(Map.of("sensitive", true), context.parameters().get(0).metadata());
        assertEquals(Map.of("sensitive", true), context.parameters().get(0).children().get(0).metadata());
        assertFalse(String.valueOf(context).contains("sensitive-example"));
    }
    private com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts.InvocationContext context(
            Map<String, Object> metadata, List<ToolDefinitionParameter> parameters, String baseUrl, String availability) {
        String hash = "a".repeat(64);
        var asset = new BusinessMethodAssetEntity();
        asset.setId(1L); asset.setProjectId(7L); asset.setProjectCode("orders"); asset.setQualifiedName("orders:lookup");
        asset.setMethodCode("lookup"); asset.setInvocationName("orders_lookup"); asset.setStatus("ACCEPTED"); asset.setEnabled(true);
        var revision = new BusinessMethodRevisionEntity(); revision.setId(2L); revision.setInvocationHash(hash);
        revision.setBindingHash("c".repeat(64));
        var declaration = new CapabilityRegistration("lookup", "查询订单", "查询订单", "POST", baseUrl, "/v1", "/orders",
                null, null, "READ_ONLY", true, parameters, metadata);
        var catalog = mock(BusinessMethodCatalogService.class);
        when(catalog.find("orders:lookup")).thenReturn(Optional.of(new BusinessMethodDefinition(asset, revision, declaration, "订单", availability)));
        var lifecycle = mock(CapabilityChangeLifecycle.class); var state = new CapabilitySourceStateEntity();
        state.setSourceContractHash(hash); state.setAcceptedContractHash(hash); state.setAvailability(availability);
        when(lifecycle.sourceState("orders:lookup")).thenReturn(state);
        var credential = new RegistryCredentialEntity(); credential.setAppKey("orders-app"); credential.setAppSecret("test-secret");
        credential.setId(3L); credential.setProjectId(7L); credential.setProjectCode("orders"); credential.setStatus("ACTIVE"); credential.setRevision(1L);
        var credentials = mock(RegistrySecurityService.class);
        when(credentials.findPrimaryActiveCredential("orders")).thenReturn(Optional.of(credential));
        return new BusinessMethodInvocationContextInternalController(new BusinessMethodInvocationContextService(
                catalog, lifecycle, credentials, json)).get("orders:lookup").getBody();
    }
}
