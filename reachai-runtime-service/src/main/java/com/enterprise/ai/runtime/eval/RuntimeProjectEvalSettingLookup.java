package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.supervisor.SupervisorProjectEvalSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.List;

/**
 * Reads one system's evaluation settings for the Agent runtime. Depends only on the settings table so
 * the runtime can use it without pulling in the evaluation services; before the table exists every
 * lookup is simply empty.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeProjectEvalSettingLookup implements SupervisorProjectEvalSettings {

    private final RuntimeProjectEvalSettingMapper settingMapper;

    /** The evaluation account of a system, for an evaluation run of its assistant; empty when none is set. */
    @Override
    public Optional<EvalAccount> evalAccount(String projectCode) {
        RuntimeProjectEvalSettingEntity setting = find(projectCode);
        if (setting == null || !StringUtils.hasText(setting.getEvalUserId())) return Optional.empty();
        return Optional.of(new EvalAccount(setting.getTenantId(), setting.getEvalUserId()));
    }

    /** Whether read-only flows learned from repeated requests may be enabled without an admin. */
    public boolean autoEnableReadonlyFlows(String projectCode) {
        RuntimeProjectEvalSettingEntity setting = find(projectCode);
        return setting != null && Boolean.TRUE.equals(setting.getAutoEnableReadonlyFlows());
    }

    private RuntimeProjectEvalSettingEntity find(String projectCode) {
        if (!StringUtils.hasText(projectCode)) return null;
        try {
            return settingMapper.selectById(projectCode.trim());
        } catch (RuntimeException unavailable) {
            log.debug("project eval settings unavailable: {}", unavailable.getMessage());
            return null;
        }
    }

    /** Systems whose administrator opted in to automatic read-only flow publication. */
    public List<String> autoEnableProjects() {
        return settingMapper.selectList(Wrappers.<RuntimeProjectEvalSettingEntity>lambdaQuery()
                        .eq(RuntimeProjectEvalSettingEntity::getAutoEnableReadonlyFlows, true))
                .stream().map(RuntimeProjectEvalSettingEntity::getProjectCode).toList();
    }
}
