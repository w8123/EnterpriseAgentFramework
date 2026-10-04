package com.enterprise.ai.capability.registry;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
import com.enterprise.ai.agent.registry.RegistryContracts.FieldDiff;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityChangePolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CapabilityChangePolicy policy = new CapabilityChangePolicy(mapper);

    @Test
    void admitsCompleteReadOnlyAdditionWithoutHumanWork() {
        CapabilityRegistration item = normalized("query", "READ_ONLY", List.of());
        assertTrue(policy.decide("ADDED", item, List.of()).automatic());
        assertFalse(policy.decide("ADDED", normalized("save", "WRITE", List.of()), List.of()).automatic());
        assertFalse(policy.decide("ADDED", normalized("save", null, List.of()), List.of()).automatic());
    }

    @Test
    void doesNotTreatMeaningOrPermissionsAsCosmeticChanges() {
        var item = normalized("query", "READ", List.of());
        assertTrue(policy.decide("CHANGED", item, List.of(new FieldDiff("title", "Old", "New"))).automatic());
        for (String field : List.of("description", "metadata", "sideEffect", "parameters", "responseType", "enabled")) {
            assertFalse(policy.decide("CHANGED", item, List.of(new FieldDiff(field, "old", "new"))).automatic(), field);
        }
        assertFalse(policy.decide("DELETED", null, List.of()).automatic());
    }

    @Test
    void parameterOrderingDoesNotCreateNewObservations() {
        var a = new ToolDefinitionParameter("a", "string", "A", false, "BODY");
        var b = new ToolDefinitionParameter("b", "number", "B", false, "BODY");
        assertEquals(policy.hash(normalized("query", "READ", List.of(a, b))),
                policy.hash(normalized("query", "READ_ONLY", List.of(b, a))));
        assertTrue(policy.equalValue("{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1}"));
        assertFalse(policy.equalValue("old value", null));
    }

    @Test
    void rejectsNameCollisionsBeforeAnyCatalogWrite() {
        assertThrows(IllegalArgumentException.class, () -> policy.normalize(project(), List.of(
                registration("query.a", "READ", List.of()), registration("query-a", "READ", List.of()))));
        assertThrows(IllegalArgumentException.class, () -> policy.normalize(project(), List.of(
                registration("query", "READ", List.of()), registration("QUERY", "READ", List.of()))));
        assertThrows(IllegalArgumentException.class, () -> policy.normalize(project(), null));
    }

    @Test
    void persistedAndObservedContractsHaveIdenticalFingerprints() throws Exception {
        var item = normalized("query", "READ", List.of(new ToolDefinitionParameter("id", "string", null, true, "BODY")));
        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setHttpMethod(item.httpMethod()); tool.setBaseUrl(item.baseUrl()); tool.setContextPath(item.contextPath());
        tool.setEndpointPath(item.endpointPath()); tool.setRequestBodyType(item.requestBodyType());
        tool.setResponseType(item.responseType()); tool.setSideEffect("READ_ONLY"); tool.setEnabled(true);
        tool.setParametersJson(mapper.writeValueAsString(item.parameters()));
        tool.setCapabilityMetadataJson("{\"sideEffect\":\"READ_ONLY\"}");
        assertEquals(policy.contractHash(item), policy.contractHash(tool));
        tool.setEndpointPath("/changed");
        assertNotEquals(policy.contractHash(item), policy.contractHash(tool));
    }

    @Test
    void validatesExplicitAssetTypesWithoutInventingLegacyMetadata() {
        CapabilityRegistration businessMethod = new CapabilityRegistration("query", "查询", "订单查询", "POST", null, null,
                "/query", "JSON", "JSON", "READ", true, List.of(), Map.of("assetType", "BUSINESS_METHOD"));
        CapabilityRegistration normalizedBusinessMethod = policy.normalize(project(), List.of(businessMethod)).get(0);
        assertEquals(CapabilityAssetType.BUSINESS_METHOD, policy.assetType(normalizedBusinessMethod));
        assertEquals("BUSINESS_METHOD", normalizedBusinessMethod.metadata().get("assetType"));

        CapabilityRegistration legacy = policy.normalize(project(), List.of(registration("legacy", "READ", List.of()))).get(0);
        assertEquals(CapabilityAssetType.UNCLASSIFIED, policy.assetType(legacy));
        assertFalse(legacy.metadata().containsKey("assetType"));

        CapabilityRegistration invalid = new CapabilityRegistration("invalid", "无效", "无效", "POST", null, null,
                "/invalid", "JSON", "JSON", "READ", true, List.of(), Map.of("assetType", "HTTP"));
        assertThrows(IllegalArgumentException.class, () -> policy.normalize(project(), List.of(invalid)));
        CapabilityRegistration nonText = new CapabilityRegistration("non-text", "无效", "无效", "POST", null, null,
                "/invalid", "JSON", "JSON", "READ", true, List.of(), Map.of("assetType", 1));
        assertThrows(IllegalArgumentException.class, () -> policy.normalize(project(), List.of(nonText)));
    }

    private CapabilityRegistration normalized(String name, String sideEffect, List<ToolDefinitionParameter> parameters) {
        return policy.normalize(project(), List.of(registration(name, sideEffect, parameters))).get(0);
    }
    private CapabilityRegistration registration(String name, String effect, List<ToolDefinitionParameter> parameters) {
        return new CapabilityRegistration(name, "查询", "订单查询", "POST", null, null,
                "/query", "JSON", "JSON", effect, true, parameters, Map.of());
    }
    private ScanProjectEntity project() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(1L); project.setProjectCode("orders"); project.setBaseUrl("https://orders.example");
        return project;
    }
}
