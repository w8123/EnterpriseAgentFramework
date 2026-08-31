package com.enterprise.ai.model.catalog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCatalogSettingsServiceTest {

    @Test
    void defaultsToDisabledWhenSingletonSettingIsMissing() {
        ModelCatalogSettingMapper mapper = mock(ModelCatalogSettingMapper.class);
        ModelCatalogSyncProperties properties = new ModelCatalogSyncProperties();

        assertFalse(new ModelCatalogSettingsService(mapper, properties).autoSyncEnabled());
    }

    @Test
    void persistedSettingOverridesDeploymentFallback() {
        ModelCatalogSettingMapper mapper = mock(ModelCatalogSettingMapper.class);
        when(mapper.selectAutoSyncEnabled()).thenReturn(true);
        ModelCatalogSyncProperties properties = new ModelCatalogSyncProperties();
        properties.setEnabled(false);

        assertTrue(new ModelCatalogSettingsService(mapper, properties).autoSyncEnabled());
    }

    @Test
    void updatePersistsExplicitConsoleChoice() {
        ModelCatalogSettingMapper mapper = mock(ModelCatalogSettingMapper.class);
        ModelCatalogSettingsService service = new ModelCatalogSettingsService(
                mapper, new ModelCatalogSyncProperties());

        assertTrue(service.updateAutoSyncEnabled(true));
        verify(mapper).upsertAutoSyncEnabled(true);
    }
}
