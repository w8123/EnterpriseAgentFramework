package com.enterprise.ai.model.catalog;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.ModelInstanceEntity;
import com.enterprise.ai.model.instance.ModelInstanceService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCatalogSettingsServiceTest {

    private final ModelInstanceService instances = mock(ModelInstanceService.class);

    @Test
    void defaultsToDisabledWhenSingletonSettingIsMissing() {
        ModelCatalogSettingMapper mapper = mock(ModelCatalogSettingMapper.class);
        ModelCatalogSyncProperties properties = new ModelCatalogSyncProperties();

        assertFalse(new ModelCatalogSettingsService(mapper, properties, instances).autoSyncEnabled());
    }

    @Test
    void persistedSettingOverridesDeploymentFallback() {
        ModelCatalogSettingMapper mapper = mock(ModelCatalogSettingMapper.class);
        when(mapper.selectAutoSyncEnabled()).thenReturn(true);
        ModelCatalogSyncProperties properties = new ModelCatalogSyncProperties();
        properties.setEnabled(false);

        assertTrue(new ModelCatalogSettingsService(mapper, properties, instances).autoSyncEnabled());
    }

    @Test
    void updatePersistsExplicitConsoleChoice() {
        ModelCatalogSettingMapper mapper = mock(ModelCatalogSettingMapper.class);
        ModelCatalogSettingsService service = new ModelCatalogSettingsService(
                mapper, new ModelCatalogSyncProperties(), instances);

        assertTrue(service.updateAutoSyncEnabled(true));
        verify(mapper).upsertAutoSyncEnabled(true);
    }

    @Test
    void consoleAnalyzerOverridesEnvironmentFallback() {
        ModelCatalogSettingMapper mapper = mock(ModelCatalogSettingMapper.class);
        ModelCatalogSyncProperties properties = new ModelCatalogSyncProperties();
        properties.setAnalyzerModelInstanceId("env-analyzer");
        ModelCatalogSettingsService service = new ModelCatalogSettingsService(mapper, properties, instances);

        assertEquals("env-analyzer", service.analyzerModelInstanceId());
        when(mapper.selectAnalyzerModelInstanceId()).thenReturn(" console-analyzer ");
        assertEquals("console-analyzer", service.analyzerModelInstanceId());
    }

    @Test
    void analyzerIsNotConfiguredWithoutConsoleOrEnvironmentChoice() {
        ModelCatalogSettingsService service = new ModelCatalogSettingsService(
                mock(ModelCatalogSettingMapper.class), new ModelCatalogSyncProperties(), instances);

        assertNull(service.analyzerModelInstanceId());
        assertFalse(service.analyzerConfigured());
    }

    @Test
    void acceptsOnlyActiveLlmInstancesAsAnalyzer() {
        ModelCatalogSettingMapper mapper = mock(ModelCatalogSettingMapper.class);
        ModelCatalogSettingsService service = new ModelCatalogSettingsService(
                mapper, new ModelCatalogSyncProperties(), instances);
        ModelInstanceEntity llm = new ModelInstanceEntity();
        llm.setModelType("LLM");
        ModelInstanceEntity embedding = new ModelInstanceEntity();
        embedding.setModelType("EMBEDDING");
        when(instances.getActiveEntity("qwen")).thenReturn(llm);
        when(instances.getActiveEntity("vector")).thenReturn(embedding);

        service.updateAnalyzerModelInstanceId("qwen");
        verify(mapper).upsertAnalyzerModelInstanceId("qwen");

        assertThrows(BizException.class, () -> service.updateAnalyzerModelInstanceId("vector"));
        verify(mapper, never()).upsertAnalyzerModelInstanceId("vector");
    }

    @Test
    void blankAnalyzerClearsConsoleChoice() {
        ModelCatalogSettingMapper mapper = mock(ModelCatalogSettingMapper.class);
        ModelCatalogSettingsService service = new ModelCatalogSettingsService(
                mapper, new ModelCatalogSyncProperties(), instances);

        service.updateAnalyzerModelInstanceId("  ");

        verify(mapper).upsertAnalyzerModelInstanceId(null);
        verify(instances, never()).getActiveEntity(any());
    }
}
