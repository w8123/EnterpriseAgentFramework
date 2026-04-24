package com.enterprise.ai.agent.tools.definition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * side_effect 一次性回填任务。
 * 默认关闭，避免每次启动都触发全表更新。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SideEffectBackfillJob implements ApplicationRunner {

    private final ToolDefinitionService toolDefinitionService;

    @Value("${ai.side-effect.backfill-on-startup:false}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        int updated = toolDefinitionService.backfillSideEffectsForTools();
        log.info("[SideEffectBackfillJob] side_effect 回填完成，更新 {} 条 TOOL 记录", updated);
    }
}
