package com.enterprise.ai.capability.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.registry.RegistryContracts.CapabilityRegistration;
import com.enterprise.ai.capability.catalog.businessmethod.*;
import com.enterprise.ai.capability.catalog.tool.CapabilityParameterContractCodec;
import com.enterprise.ai.capability.registry.CapabilityChangePolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Adapts pre-existing transport fixtures to mock owner facts; it does not exercise owner acceptance. */
public final class BusinessMethodExecutionFixtures {
    public static String executionRevision(ToolDefinitionEntity fixture) {
        var credential = new com.enterprise.ai.agent.registry.RegistryCredentialEntity();
        credential.setId(1L); credential.setRevision(1L); credential.setStatus("ACTIVE");
        credential.setProjectId(fixture.getProjectId()); credential.setProjectCode(fixture.getProjectCode());
        credential.setAppKey("fixture-key"); credential.setAppSecret("fixture-secret");
        return com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodExecutionRevision.of(definition(fixture), credential);
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private BusinessMethodExecutionFixtures() { }

    public static BusinessMethodCatalogService catalog(ToolDefinitionMapper transportFixtures) {
        var catalog = mock(BusinessMethodCatalogService.class);
        when(catalog.find(anyString())).thenAnswer(call -> {
            ToolDefinitionEntity fixture = transportFixtures.selectOne(Wrappers.<ToolDefinitionEntity>lambdaQuery()
                    .eq(ToolDefinitionEntity::getQualifiedName, call.getArgument(0)));
            if (fixture == null || "HTTP_API".equals(fixture.getAssetType())) return Optional.empty();
            return Optional.of(definition(fixture));
        });
        return catalog;
    }

    public static BusinessMethodDefinition definition(ToolDefinitionEntity fixture) {
        try {
            var asset = new BusinessMethodAssetEntity();
            asset.setId(fixture.getId()); asset.setInvocationName(fixture.getName());
            asset.setQualifiedName(fixture.getQualifiedName()); asset.setMethodCode(fixture.getName());
            asset.setProjectId(fixture.getProjectId()); asset.setProjectCode(fixture.getProjectCode());
            String sdkPrefix = "sdk:" + fixture.getProjectCode() + ":";
            if (fixture.getSourceLocation() != null && fixture.getSourceLocation().startsWith(sdkPrefix)) {
                asset.setMethodCode(fixture.getSourceLocation().substring(sdkPrefix.length()));
            }
            asset.setEnabled(fixture.getEnabled()); asset.setStatus("ACCEPTED");
            asset.setCreatedAt(fixture.getCreateTime()); asset.setUpdatedAt(fixture.getUpdateTime());
            Map<String, Object> metadata = fixture.getCapabilityMetadataJson() == null ? Map.of()
                    : JSON.readValue(fixture.getCapabilityMetadataJson(), new TypeReference<>() { });
            var declaration = new CapabilityRegistration(fixture.getName(), fixture.getTitle(), fixture.getDescription(),
                    fixture.getHttpMethod(), fixture.getBaseUrl(), fixture.getContextPath(), fixture.getEndpointPath(),
                    fixture.getRequestBodyType(), fixture.getResponseType(), CapabilityChangePolicy.sideEffect(fixture.getSideEffect()),
                    fixture.getEnabled(), CapabilityParameterContractCodec.parse(JSON, fixture.getParametersJson()), metadata);
            var revision = new BusinessMethodRevisionEntity(); revision.setId(1L); revision.setAssetId(asset.getId());
            revision.setInvocationHash(new CapabilityChangePolicy(JSON).contractHash(fixture));
            revision.setContractHash(revision.getInvocationHash());
            revision.setBindingHash(revision.getInvocationHash());
            return new BusinessMethodDefinition(asset, revision, declaration, null, "READY");
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid owner transport fixture", invalid); }
    }
}
