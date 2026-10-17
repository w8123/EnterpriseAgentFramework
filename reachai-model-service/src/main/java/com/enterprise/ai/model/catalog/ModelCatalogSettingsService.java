package com.enterprise.ai.model.catalog;

import com.enterprise.ai.common.exception.BizException;
import com.enterprise.ai.model.instance.ModelInstanceEntity;
import com.enterprise.ai.model.instance.ModelInstanceService;
import com.enterprise.ai.model.instance.ModelType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ModelCatalogSettingsService {

    private final ModelCatalogSettingMapper settingMapper;
    private final ModelCatalogSyncProperties properties;
    private final ModelInstanceService modelInstanceService;

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

    /**
     * 目录分析模型：界面保存的设置优先，未设置时回退到部署环境变量。
     */
    public String analyzerModelInstanceId() {
        String persisted = settingMapper.selectAnalyzerModelInstanceId();
        if (StringUtils.hasText(persisted)) {
            return persisted.trim();
        }
        String configured = properties.getAnalyzerModelInstanceId();
        return StringUtils.hasText(configured) ? configured.trim() : null;
    }

    public boolean analyzerConfigured() {
        return analyzerModelInstanceId() != null;
    }

    /**
     * 只接受 ACTIVE 的 LLM 实例，绝不暗选业务模型；空字符串清除界面设置。
     */
    public void updateAnalyzerModelInstanceId(String instanceId) {
        if (instanceId == null) {
            throw new BizException(400, "analyzerModelInstanceId is required");
        }
        String trimmed = instanceId.trim();
        if (trimmed.isEmpty()) {
            settingMapper.upsertAnalyzerModelInstanceId(null);
            return;
        }
        ModelInstanceEntity entity = modelInstanceService.getActiveEntity(trimmed);
        if (!ModelType.LLM.name().equals(entity.getModelType())) {
            throw new BizException(400, "目录分析模型必须是大语言模型");
        }
        settingMapper.upsertAnalyzerModelInstanceId(trimmed);
    }
}
