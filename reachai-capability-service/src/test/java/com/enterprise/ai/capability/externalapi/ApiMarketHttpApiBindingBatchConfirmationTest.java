package com.enterprise.ai.capability.externalapi;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.capability.catalog.httpapi.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiMarketHttpApiBindingBatchConfirmationTest {
    @BeforeAll
    static void mappingMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "market-batch-test"),
                ProjectExternalApiEntity.class);
    }

    private final ProjectExternalApiMapper integrations = mock(ProjectExternalApiMapper.class);
    private final ProjectExternalApiOperationMapper selections = mock(ProjectExternalApiOperationMapper.class);
    private final ExternalApiEntryMapper entries = mock(ExternalApiEntryMapper.class);
    private final ExternalApiVersionMapper versions = mock(ExternalApiVersionMapper.class);
    private final ExternalApiOperationMapper operations = mock(ExternalApiOperationMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final HttpApiContractCanonicalizer canonicalizer = new HttpApiContractCanonicalizer(json);
    private final ApiMarketHttpApiBindingService service = new ApiMarketHttpApiBindingService(
            mock(HttpApiAssetService.class), mock(HttpApiAssetMapper.class), mock(HttpApiSourceBindingMapper.class),
            integrations, selections, entries, versions, operations, canonicalizer, json);

    @Test
    void successfulBindingsUseOneReadOfEachOwnerTableAndKeepExplicitNullResults() {
        Fixture fixture = fixture();
        var reasons = service.confirmationReasons(fixture.assets(), fixture.sources(), Map.of(7L, fixture.project()));
        assertEquals(2, reasons.size());
        assertTrue(reasons.containsKey(101L));
        assertTrue(reasons.containsKey(102L));
        assertNull(reasons.get(101L));
        assertNull(reasons.get(102L));
        verify(selections, times(1)).selectList(any());
        verify(integrations, times(1)).selectBatchIds(any());
        verify(entries, times(1)).selectBatchIds(any());
        verify(versions, times(1)).selectBatchIds(any());
        verify(operations, times(1)).selectBatchIds(any());
        verify(integrations, never()).update(any(), any());
        verify(integrations, never()).selectById(any());
        verify(operations, never()).selectById(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneBrokenOperationInvalidatesEveryBindingForTheSameIntegrationRegardlessOfOrder(boolean brokenFirst) {
        Fixture fixture = fixture();
        fixture.sources().get(1).setSourceRevision("stale-selection");
        List<HttpApiSourceBindingEntity> sourceRows = brokenFirst
                ? List.of(fixture.sources().get(1), fixture.sources().get(0)) : fixture.sources();
        when(integrations.update(isNull(), any())).thenReturn(1);

        var reasons = service.confirmationReasons(fixture.assets(), sourceRows, Map.of(7L, fixture.project()));

        assertNotNull(reasons.get(101L));
        assertNotNull(reasons.get(102L));
        verify(integrations, times(1)).update(isNull(), argThat(wrapper -> {
            var update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ProjectExternalApiEntity>) wrapper;
            assertTrue(update.getSqlSegment().contains("selection_revision"));
            assertTrue(update.getSqlSet().contains("status"));
            return update.getParamNameValuePairs().values().containsAll(List.of(4L, 3L, "BROKEN"));
        }));
    }

    @Test
    void selectionRevisionRaceDoesNotConfirmOtherBindingsFromTheStaleBatch() {
        Fixture fixture = fixture();
        fixture.sources().get(1).setSourceRevision("stale-selection");
        when(integrations.update(isNull(), any())).thenReturn(0);
        var reasons = service.confirmationReasons(fixture.assets(), fixture.sources(), Map.of(7L, fixture.project()));
        assertNotNull(reasons.get(101L));
        assertNotNull(reasons.get(102L));
        assertEquals("READY", fixture.integration().getStatus(), "A failed CAS keeps this request-local owner snapshot unchanged.");
    }

    @Test
    void singleLookupStillReadsFreshOwnersAfterTheCatalogBatch() {
        Fixture fixture = fixture();
        assertNull(service.confirmationReasons(fixture.assets(), fixture.sources(), Map.of(7L, fixture.project())).get(101L));
        ExternalApiOperationEntity changed = fixture.operations().get(0);
        changed.setStatus("DISABLED");
        when(selections.selectList(any())).thenReturn(List.of(fixture.selections().get(0)));
        when(integrations.selectById(4L)).thenReturn(fixture.integration());
        when(entries.selectById(21L)).thenReturn(fixture.entry());
        when(versions.selectById(31L)).thenReturn(fixture.version());
        when(operations.selectById(changed.getId())).thenReturn(changed);

        assertNotNull(service.confirmationReason(fixture.assets().get(0), fixture.sources().get(0), fixture.project()));
        verify(operations).selectById(changed.getId());
        verify(integrations).update(isNull(), any());
    }

    @Test
    void requestSessionSharesIntegrationOwnersAndRejectsEarlierAssetsAfterLateInvalidation() {
        Fixture fixture = fixture();
        var session = new ApiMarketHttpApiBindingService.ConfirmationSession();
        when(selections.selectList(any())).thenReturn(List.of(fixture.selections().get(0)), List.of(fixture.selections().get(1)));
        assertNull(service.confirmationReasons(List.of(fixture.assets().get(0)), List.of(fixture.sources().get(0)),
                Map.of(7L, fixture.project()), session).get(101L));
        fixture.sources().get(1).setSourceRevision("stale-selection");
        assertNotNull(service.confirmationReasons(List.of(fixture.assets().get(1)), List.of(fixture.sources().get(1)),
                Map.of(7L, fixture.project()), session).get(102L));
        assertTrue(session.invalidated(fixture.assets().get(0).getId()));
        assertTrue(session.invalidated(fixture.assets().get(1).getId()));
        verify(integrations, times(1)).selectBatchIds(any());
        verify(entries, times(1)).selectBatchIds(any());
        verify(versions, times(1)).selectBatchIds(any());
        verify(integrations, times(1)).update(isNull(), any());
    }

    @Test
    void nonMarketSourcesNeedNoMarketOwnerReads() {
        var source = new HttpApiSourceBindingEntity();
        source.setSourceKind("CONTROLLER_SCAN");
        assertTrue(service.confirmationReasons(List.of(), List.of(source), Map.of()).isEmpty());
        verifyNoInteractions(integrations, selections, entries, versions, operations);
    }

    private Fixture fixture() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L); project.setProjectCode("orders"); project.setEnvironment("dev");
        ExternalApiEntryEntity entry = new ExternalApiEntryEntity();
        entry.setId(21L); entry.setEntryKey("orders"); entry.setPublicationStatus("PUBLISHED"); entry.setAuthType("NONE");
        ExternalApiVersionEntity version = new ExternalApiVersionEntity();
        version.setId(31L); version.setEntryId(21L); version.setVersionKey("v1");
        version.setPublicationStatus("PUBLISHED"); version.setSpecHash("a".repeat(64));
        ProjectExternalApiEntity integration = new ProjectExternalApiEntity();
        integration.setId(4L); integration.setProjectId(7L); integration.setProjectCode("orders");
        integration.setEnvironment("dev"); integration.setEntryId(21L); integration.setVersionId(31L);
        integration.setSelectionRevision(3L); integration.setStatus("READY");
        ExternalApiOperationEntity first = operation(41L, "find-a", "/orders/a");
        ExternalApiOperationEntity second = operation(42L, "find-b", "/orders/b");
        List<ExternalApiOperationEntity> operationRows = List.of(first, second);
        List<HttpApiAssetEntity> assetRows = new java.util.ArrayList<>();
        List<HttpApiSourceBindingEntity> sourceRows = new java.util.ArrayList<>();
        List<ProjectExternalApiOperationEntity> selectedRows = new java.util.ArrayList<>();
        for (int index = 0; index < operationRows.size(); index++) {
            var operation = operationRows.get(index);
            var observed = service.prepare(project, entry, version, operation, 3L);
            var canonical = canonicalizer.canonicalize(observed.scope(), observed.contract());
            var asset = new HttpApiAssetEntity();
            asset.setId(11L + index); asset.setProjectId(7L); asset.setProjectCode("orders");
            asset.setEnvironment("dev"); asset.setQualifiedName(canonical.qualifiedName());
            assetRows.add(asset);
            var source = new HttpApiSourceBindingEntity();
            source.setId(101L + index); source.setAssetId(asset.getId());
            source.setSourceKind(HttpApiSourceKind.API_MARKET_OPERATION.name());
            source.setSourceKey(observed.sourceKey()); source.setSourceRevision(observed.sourceRevision());
            source.setSourceContractHash(canonical.contractHash()); source.setSourceContractJson(canonical.contractJson());
            sourceRows.add(source);
            var selected = new ProjectExternalApiOperationEntity();
            selected.setApiAssetId(asset.getId()); selected.setIntegrationId(4L); selected.setOperationId(operation.getId());
            selectedRows.add(selected);
        }
        when(selections.selectList(any())).thenReturn(selectedRows);
        when(integrations.selectBatchIds(any())).thenReturn(List.of(integration));
        when(entries.selectBatchIds(any())).thenReturn(List.of(entry));
        when(versions.selectBatchIds(any())).thenReturn(List.of(version));
        when(operations.selectBatchIds(any())).thenReturn(operationRows);
        return new Fixture(project, entry, version, integration, operationRows, assetRows, sourceRows, selectedRows);
    }

    private ExternalApiOperationEntity operation(Long id, String key, String path) {
        var operation = new ExternalApiOperationEntity();
        operation.setId(id); operation.setVersionId(31L); operation.setOperationKey(key); operation.setPath(path);
        operation.setHttpMethod("GET"); operation.setSideEffect("READ_ONLY"); operation.setAuthRequired(false);
        operation.setStatus("ACTIVE"); operation.setResponseStatus(200); operation.setResponseContentType("application/json");
        operation.setRequestSchemaJson("{\"type\":\"object\",\"properties\":{}}");
        operation.setResponseSchemaJson("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
        return operation;
    }

    private record Fixture(ScanProjectEntity project, ExternalApiEntryEntity entry, ExternalApiVersionEntity version,
                           ProjectExternalApiEntity integration, List<ExternalApiOperationEntity> operations,
                           List<HttpApiAssetEntity> assets, List<HttpApiSourceBindingEntity> sources,
                           List<ProjectExternalApiOperationEntity> selections) { }
}
