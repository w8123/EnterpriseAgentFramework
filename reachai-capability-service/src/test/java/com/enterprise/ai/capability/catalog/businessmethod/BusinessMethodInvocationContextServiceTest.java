package com.enterprise.ai.capability.catalog.businessmethod;

import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistrySecurityService;
import com.enterprise.ai.capability.catalog.tool.CapabilityToolCatalogService;
import com.enterprise.ai.capability.registry.CapabilityChangeLifecycle;
import com.enterprise.ai.capability.registry.CapabilityChangePolicy;
import com.enterprise.ai.capability.registry.CapabilitySourceStateEntity;
import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regression coverage for the owner-side SDK declaration read, through its real internal controller. */
class BusinessMethodInvocationContextServiceTest {

    @Test
    void emptySdkRequiredRolesRemainExecutableThroughTheOwnerController() {
        String hash = "a".repeat(64);
        ToolDefinitionEntity tool = sdkBusinessMethod("{\"requiredRoles\":[]}");
        CapabilityToolCatalogService catalog = mock(CapabilityToolCatalogService.class);
        CapabilityChangeLifecycle lifecycle = mock(CapabilityChangeLifecycle.class);
        CapabilityChangePolicy policy = mock(CapabilityChangePolicy.class);
        RegistrySecurityService credentials = mock(RegistrySecurityService.class);
        CapabilitySourceStateEntity state = new CapabilitySourceStateEntity();
        state.setSourceContractHash(hash);
        state.setAcceptedContractHash(hash);
        state.setAvailability("READY");
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("orders-app");
        credential.setAppSecret("test-secret");
        when(catalog.findBusinessMethodByName("orders.lookup")).thenReturn(Optional.of(tool));
        when(catalog.parseParameters(null)).thenReturn(List.of());
        when(policy.contractHash(tool)).thenReturn(hash);
        when(lifecycle.sourceState("orders.lookup")).thenReturn(state);
        when(credentials.findPrimaryActiveCredential("orders")).thenReturn(Optional.of(credential));

        BusinessMethodInvocationContextService service = new BusinessMethodInvocationContextService(
                catalog, lifecycle, policy, credentials, new ObjectMapper());
        BusinessMethodInvocationContextInternalController controller =
                new BusinessMethodInvocationContextInternalController(service);

        var response = controller.get("orders.lookup");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ConsoleCapabilityInvocationContracts.InvocationContext context = response.getBody();
        assertFalse(context.businessIdentityRequired());
        assertTrue(context.executable());
    }

    @Test
    void requiredRoleInterpretationIsNarrowAndFailClosedForMalformedMetadata() {
        BusinessMethodInvocationContextService service = new BusinessMethodInvocationContextService(
                mock(CapabilityToolCatalogService.class), mock(CapabilityChangeLifecycle.class),
                mock(CapabilityChangePolicy.class), mock(RegistrySecurityService.class), new ObjectMapper());

        assertFalse(service.hasRequiredRoles(sdkBusinessMethod(null)));
        assertFalse(service.hasRequiredRoles(sdkBusinessMethod("{\"requiredRoles\":[]}")));
        assertFalse(service.hasRequiredRoles(sdkBusinessMethod("{\"requiredRoles\":[\"\",\"  \"]}")));
        assertTrue(service.hasRequiredRoles(sdkBusinessMethod("{\"requiredRoles\":[\"order-reader\"]}")));
        assertTrue(service.hasRequiredRoles(sdkBusinessMethod("{not-json")));
    }

    @Test
    void contextKeepsWhitelistedNormalMetadataButRedactsSensitiveExamplesAndAddressSecrets() {
        String hash = "a".repeat(64);
        ToolDefinitionEntity tool = sdkBusinessMethod("{\"requiredRoles\":[],\"timeoutMs\":120000}");
        tool.setHttpMethod("post");
        tool.setBaseUrl("https://user:private@example.test:8443/api?access_token=private");
        tool.setContextPath("/v1");
        tool.setEndpointPath("/orders");
        CapabilityToolCatalogService catalog = mock(CapabilityToolCatalogService.class);
        CapabilityChangeLifecycle lifecycle = mock(CapabilityChangeLifecycle.class);
        CapabilityChangePolicy policy = mock(CapabilityChangePolicy.class);
        RegistrySecurityService credentials = mock(RegistrySecurityService.class);
        CapabilitySourceStateEntity state = new CapabilitySourceStateEntity();
        state.setSourceContractHash(hash); state.setAcceptedContractHash(hash); state.setAvailability("READY");
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("orders-app"); credential.setAppSecret("test-secret");
        ToolDefinitionParameter normal = new ToolDefinitionParameter("request", "object", "request", true, null,
                List.of(), Map.of("example", Map.of("quantity", 2), "enum", List.of("standard"), "minimum", 1));
        ToolDefinitionParameter sensitive = new ToolDefinitionParameter("password", "string", "secret", false, null,
                List.of(), Map.of("sensitive", true, "default", "private", "example", "private"));
        when(catalog.findBusinessMethodByName("orders.lookup")).thenReturn(Optional.of(tool));
        when(catalog.parseParameters(null)).thenReturn(List.of(normal, sensitive));
        when(policy.contractHash(tool)).thenReturn(hash);
        when(lifecycle.sourceState("orders.lookup")).thenReturn(state);
        when(credentials.findPrimaryActiveCredential("orders")).thenReturn(Optional.of(credential));

        var context = new BusinessMethodInvocationContextService(catalog, lifecycle, policy, credentials,
                new ObjectMapper()).get("orders.lookup");

        assertEquals(60_000L, context.timeoutMs());
        assertEquals("POST · https://example.test:8443/api/v1/orders", context.targetDescription());
        assertFalse(context.targetDescription().contains("private"));
        assertEquals(Map.of("example", Map.of("quantity", 2), "enum", List.of("standard"), "minimum", 1),
                context.parameters().get(0).metadata());
        assertEquals(Map.of("sensitive", true), context.parameters().get(1).metadata());
    }

    @Test
    void sensitiveParentMarksNestedDeclarationsSensitiveWithoutLeakingExamples() {
        String hash = "a".repeat(64);
        ToolDefinitionEntity tool = sdkBusinessMethod("{\"requiredRoles\":[]}");
        CapabilityToolCatalogService catalog = mock(CapabilityToolCatalogService.class);
        CapabilityChangeLifecycle lifecycle = mock(CapabilityChangeLifecycle.class);
        CapabilityChangePolicy policy = mock(CapabilityChangePolicy.class);
        RegistrySecurityService credentials = mock(RegistrySecurityService.class);
        CapabilitySourceStateEntity state = new CapabilitySourceStateEntity();
        state.setSourceContractHash(hash); state.setAcceptedContractHash(hash); state.setAvailability("READY");
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setAppKey("orders-app"); credential.setAppSecret("test-secret");
        ToolDefinitionParameter nestedPhone = new ToolDefinitionParameter("phone", "string", "phone", false, null,
                List.of(), Map.of("example", "sensitive-example", "default", "sensitive-default"));
        ToolDefinitionParameter sensitiveRequest = new ToolDefinitionParameter("request", "object", "request", true,
                null, List.of(nestedPhone), Map.of("sensitive", true, "example", Map.of("phone", "sensitive-example")));
        when(catalog.findBusinessMethodByName("orders.lookup")).thenReturn(Optional.of(tool));
        when(catalog.parseParameters(null)).thenReturn(List.of(sensitiveRequest));
        when(policy.contractHash(tool)).thenReturn(hash);
        when(lifecycle.sourceState("orders.lookup")).thenReturn(state);
        when(credentials.findPrimaryActiveCredential("orders")).thenReturn(Optional.of(credential));

        var context = new BusinessMethodInvocationContextService(catalog, lifecycle, policy, credentials,
                new ObjectMapper()).get("orders.lookup");

        assertEquals(Map.of("sensitive", true), context.parameters().get(0).metadata());
        assertEquals(Map.of("sensitive", true), context.parameters().get(0).children().get(0).metadata());
        assertFalse(String.valueOf(context).contains("sensitive-example"));
        assertFalse(String.valueOf(context).contains("sensitive-default"));
    }

    private ToolDefinitionEntity sdkBusinessMethod(String metadata) {
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setName("orders.lookup");
        tool.setQualifiedName("orders.lookup");
        tool.setSourceQualifiedName("orders.lookup");
        tool.setAssetType("BUSINESS_METHOD");
        tool.setEnabled(true);
        tool.setProjectId(7L);
        tool.setProjectCode("orders");
        tool.setSideEffect("READ_ONLY");
        tool.setCapabilityMetadataJson(metadata);
        return tool;
    }
}
