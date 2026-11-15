package com.enterprise.ai.runtime.eval;

import com.enterprise.ai.runtime.supervisor.SupervisorProjectEvalSettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeProjectEvalSettingLookupTest {
    private final RuntimeProjectEvalSettingMapper mapper = mock(RuntimeProjectEvalSettingMapper.class);
    private final RuntimeProjectEvalSettingLookup lookup = new RuntimeProjectEvalSettingLookup(mapper);

    @Test
    void supervisorReadsOnlyTheConfiguredEvaluationIdentityAndFailsClosedWhenUnavailable() {
        var row = new RuntimeProjectEvalSettingEntity();
        row.setTenantId("tenant");
        row.setEvalUserId("readonly-employee");
        when(mapper.selectById("erp")).thenReturn(row);
        SupervisorProjectEvalSettings settings = lookup;

        assertEquals(new SupervisorProjectEvalSettings.EvalAccount("tenant", "readonly-employee"),
                settings.evalAccount(" erp ").orElseThrow());
        when(mapper.selectById("erp")).thenThrow(new IllegalStateException("unavailable"));
        assertTrue(settings.evalAccount("erp").isEmpty());
    }

    @Test
    void onlyOptedInSystemsAreQueriedForAutomaticFlowPublication() {
        var row = new RuntimeProjectEvalSettingEntity();
        row.setProjectCode("erp");
        when(mapper.selectList(any())).thenAnswer(call -> {
            var query = call.getArgument(0, com.baomidou.mybatisplus.core.conditions.Wrapper.class);
            assertTrue(query.getSqlSegment().contains("auto_enable_readonly_flows"));
            return List.of(row);
        });
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "project-eval-lookup"),
                RuntimeProjectEvalSettingEntity.class);

        assertEquals(List.of("erp"), lookup.autoEnableProjects());
    }
}
