package com.enterprise.ai.model.catalog;

import com.enterprise.ai.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ModelCatalogSettingsService {

    private final ModelCatalogSettingMapper settingMapper;
    private final ModelCatalogSyncProperties properties;

    public boolean autoSyncEnabled() {
        Boolean persisted = settingMapper.selectAutoSyncEnabled();
        return persisted == null ? properties.isEnabled() : persisted;
    }

    public boolean updateAutoSyncEnabled(Boolean enabled) {
        if (enabled == null) {
            throw new BizException(400, "autoSyncEnabled is required");
        }
        settingMapper.upsertAutoSyncEnabled(enabled);
        return enabled;
    }
}
