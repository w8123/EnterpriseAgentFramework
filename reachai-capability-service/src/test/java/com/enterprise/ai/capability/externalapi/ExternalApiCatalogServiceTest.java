package com.enterprise.ai.capability.externalapi;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalApiCatalogServiceTest {

    private final ExternalApiSourceMapper sourceMapper = mock(ExternalApiSourceMapper.class);
    private final ExternalApiProviderMapper providerMapper = mock(ExternalApiProviderMapper.class);
    private final ExternalApiEntryMapper entryMapper = mock(ExternalApiEntryMapper.class);
    private final ExternalApiVersionMapper versionMapper = mock(ExternalApiVersionMapper.class);
    private final ExternalApiOperationMapper operationMapper = mock(ExternalApiOperationMapper.class);
    private final ExternalApiVerificationMapper verificationMapper = mock(ExternalApiVerificationMapper.class);
    private final ProjectExternalApiMapper integrationMapper = mock(ProjectExternalApiMapper.class);
    private final ProjectExternalApiOperationMapper integrationOperationMapper =
            mock(ProjectExternalApiOperationMapper.class);
    private final ScanProjectMapper projectMapper = mock(ScanProjectMapper.class);
    private final ExternalApiCatalogService service = new ExternalApiCatalogService(
            sourceMapper,
            providerMapper,
            entryMapper,
            versionMapper,
            operationMapper,
            verificationMapper,
            integrationMapper,
            integrationOperationMapper,
            projectMapper,
            new ObjectMapper()
    );

    @Test
    void returnsPublishedCatalogEntriesWithProductMetadata() {
        ExternalApiEntryEntity entry = entry();
        Page<ExternalApiEntryEntity> page = new Page<>(1, 24, 1);
        page.setRecords(List.of(entry));
        when(entryMapper.selectPage(any(), any())).thenReturn(page);

        ExternalApiCatalogViews.PageView result = service.listEntries(
                1, 24, null, null, null, null, null, null, null);

        assertEquals(1, result.total());
        assertEquals("open-meteo", result.records().get(0).entryKey());
        assertEquals(List.of("天气", "预报"), result.records().get(0).tags());
        assertEquals("VERIFIED", result.records().get(0).verificationStatus());
    }

    @Test
    void returnsBoundedVerificationEvidenceWithoutResponseBodies() {
        ExternalApiVerificationEntity verification = new ExternalApiVerificationEntity();
        verification.setId(51L);
        verification.setEntryId(11L);
        verification.setVersionId(21L);
        verification.setVerificationType("CONNECTIVITY");
        verification.setStatus("VERIFIED");
        verification.setHttpStatus(200);
        verification.setLatencyMs(128L);
        verification.setCheckedUrl("https://api.open-meteo.com/v1/forecast");
        verification.setEvidenceSummary("只读请求返回 HTTP 200；未保存响应正文。");

        when(entryMapper.selectOne(any())).thenReturn(entry());
        when(versionMapper.selectList(any())).thenReturn(List.of(version()));
        when(operationMapper.selectList(any())).thenReturn(List.of(operation()));
        when(verificationMapper.selectList(any())).thenReturn(List.of(verification));

        ExternalApiCatalogViews.EntryDetail result = service.getEntry("open-meteo");

        assertEquals(1, result.verifications().size());
        assertEquals(200, result.verifications().get(0).httpStatus());
        assertEquals("只读请求返回 HTTP 200；未保存响应正文。",
                result.verifications().get(0).evidenceSummary());
    }

    @Test
    void createsProjectIntegrationWithoutPersistingCredentials() {
        ExternalApiEntryEntity entry = entry();
        ExternalApiVersionEntity version = version();
        ExternalApiOperationEntity operation = operation();
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");
        project.setName("订单系统");
        project.setEnvironment("DEV");

        when(entryMapper.selectOne(any())).thenReturn(entry);
        when(projectMapper.selectById(7L)).thenReturn(project);
        when(versionMapper.selectById(21L)).thenReturn(version);
        when(operationMapper.selectBatchIds(any())).thenReturn(List.of(operation));
        when(integrationMapper.selectOne(any())).thenReturn(null);
        when(integrationMapper.insert(any())).thenAnswer(invocation -> {
            ProjectExternalApiEntity entity = invocation.getArgument(0);
            entity.setId(31L);
            return 1;
        });

        ExternalApiCatalogViews.IntegrationView result = service.createIntegration(
                "open-meteo",
                new ExternalApiCatalogViews.IntegrationCreateRequest(
                        7L, "orders", 21L, List.of(41L), "DEV", "天气查询"));

        assertEquals(31L, result.id());
        assertEquals("READY", result.status());
        assertEquals(false, result.credentialRequired());
        assertEquals("forecast", result.selectedOperations().get(0).operationKey());
        verify(integrationOperationMapper).insert(any());
    }

    @Test
    void rejectsAnOperationFromAnotherApiVersion() {
        ExternalApiEntryEntity entry = entry();
        ExternalApiVersionEntity version = version();
        ExternalApiOperationEntity foreignOperation = operation();
        foreignOperation.setVersionId(999L);
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");

        when(entryMapper.selectOne(any())).thenReturn(entry);
        when(projectMapper.selectById(7L)).thenReturn(project);
        when(versionMapper.selectById(21L)).thenReturn(version);
        when(operationMapper.selectBatchIds(any())).thenReturn(List.of(foreignOperation));

        ExternalApiCatalogException error = assertThrows(
                ExternalApiCatalogException.class,
                () -> service.createIntegration(
                        "open-meteo",
                        new ExternalApiCatalogViews.IntegrationCreateRequest(
                                7L, "orders", 21L, List.of(41L), "DEV", null)));

        assertEquals("API_MARKET_OPERATION_INVALID", error.code());
    }

    @Test
    void rejectsAnUnpublishedExplicitVersion() {
        ExternalApiEntryEntity entry = entry();
        ExternalApiVersionEntity version = version();
        version.setPublicationStatus("DRAFT");
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");

        when(entryMapper.selectOne(any())).thenReturn(entry);
        when(projectMapper.selectById(7L)).thenReturn(project);
        when(versionMapper.selectById(21L)).thenReturn(version);

        ExternalApiCatalogException error = assertThrows(
                ExternalApiCatalogException.class,
                () -> service.createIntegration(
                        "open-meteo",
                        new ExternalApiCatalogViews.IntegrationCreateRequest(
                                7L, "orders", 21L, List.of(41L), "DEV", null)));

        assertEquals("API_MARKET_VERSION_INVALID", error.code());
        verify(integrationMapper, never()).insert(any());
    }

    @Test
    void refusesToClaimCredentialBackedIntegrationIsReadyWithoutRuntimeProof() {
        ProjectExternalApiEntity integration = new ProjectExternalApiEntity();
        integration.setId(31L);
        integration.setEntryId(11L);
        ExternalApiEntryEntity entry = entry();
        entry.setAuthType("BEARER");

        when(integrationMapper.selectById(31L)).thenReturn(integration);
        when(entryMapper.selectById(11L)).thenReturn(entry);

        ExternalApiCatalogException error = assertThrows(
                ExternalApiCatalogException.class,
                () -> service.updateIntegrationStatus(
                        31L, new ExternalApiCatalogViews.IntegrationStatusRequest("READY", null)));

        assertEquals("API_MARKET_CREDENTIAL_PROOF_REQUIRED", error.code());
        verify(integrationMapper, never()).updateById(any());
    }

    @Test
    void rejectsUnknownIntegrationEnvironment() {
        ScanProjectEntity project = new ScanProjectEntity();
        project.setId(7L);
        project.setProjectCode("orders");

        when(entryMapper.selectOne(any())).thenReturn(entry());
        when(projectMapper.selectById(7L)).thenReturn(project);
        when(versionMapper.selectById(21L)).thenReturn(version());
        when(operationMapper.selectBatchIds(any())).thenReturn(List.of(operation()));

        ExternalApiCatalogException error = assertThrows(
                ExternalApiCatalogException.class,
                () -> service.createIntegration(
                        "open-meteo",
                        new ExternalApiCatalogViews.IntegrationCreateRequest(
                                7L, "orders", 21L, List.of(41L), "personal-laptop", null)));

        assertEquals("API_MARKET_ENVIRONMENT_INVALID", error.code());
        verify(integrationMapper, never()).insert(any());
    }

    private ExternalApiEntryEntity entry() {
        ExternalApiEntryEntity entry = new ExternalApiEntryEntity();
        entry.setId(11L);
        entry.setEntryKey("open-meteo");
        entry.setTitle("Open-Meteo");
        entry.setSummary("全球天气预报");
        entry.setCategoryCode("WEATHER");
        entry.setTagsJson("[\"天气\",\"预报\"]");
        entry.setAuthType("NONE");
        entry.setPricingType("FREE");
        entry.setHttpsSupported(true);
        entry.setPublicationStatus("PUBLISHED");
        entry.setVerificationStatus("VERIFIED");
        entry.setSpecStatus("VALID");
        entry.setFeatured(true);
        entry.setPopularityScore(95);
        return entry;
    }

    private ExternalApiVersionEntity version() {
        ExternalApiVersionEntity version = new ExternalApiVersionEntity();
        version.setId(21L);
        version.setEntryId(11L);
        version.setVersionKey("v1");
        version.setBaseUrl("https://api.open-meteo.com");
        version.setPublicationStatus("PUBLISHED");
        return version;
    }

    private ExternalApiOperationEntity operation() {
        ExternalApiOperationEntity operation = new ExternalApiOperationEntity();
        operation.setId(41L);
        operation.setVersionId(21L);
        operation.setOperationKey("forecast");
        operation.setTitle("天气预报");
        operation.setHttpMethod("GET");
        operation.setPath("/v1/forecast");
        operation.setSideEffect("READ_ONLY");
        operation.setAuthRequired(false);
        operation.setExampleParamsJson("{\"latitude\":39.9,\"longitude\":116.4}");
        operation.setStatus("ACTIVE");
        return operation;
    }
}
