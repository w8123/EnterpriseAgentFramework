package com.enterprise.ai.agent.platform.control.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.assist.AiAccessSessionEntity;
import com.enterprise.ai.agent.assist.AiAccessSessionMapper;
import com.enterprise.ai.agent.assist.AiAccessStepEntity;
import com.enterprise.ai.agent.assist.AiAccessStepMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PageRegistryManagementService {

    private static final String PAGE_ASSISTANT_SCENARIO = "PAGE_ASSISTANT";

    private final PageRegistryMapper pageRegistryMapper;
    private final PageActionRegistryMapper pageActionRegistryMapper;
    private final EmbedSessionMapper embedSessionMapper;
    private final PageActionEventMapper pageActionEventMapper;
    private final EmbedChatEventMapper embedChatEventMapper;
    private final AiAccessSessionMapper aiAccessSessionMapper;
    private final AiAccessStepMapper aiAccessStepMapper;

    @Transactional
    public PageRegistryDeleteResult deletePageAndRelations(Long pageId) {
        if (pageId == null) {
            throw new IllegalArgumentException("page id is required");
        }
        PageRegistryEntity page = pageRegistryMapper.selectById(pageId);
        if (page == null) {
            throw new IllegalArgumentException("frontend page not found: " + pageId);
        }
        String projectCode = page.getProjectCode();
        String pageKey = page.getPageKey();

        List<EmbedSessionEntity> embedSessions = embedSessionMapper.selectList(
                Wrappers.<EmbedSessionEntity>lambdaQuery()
                        .eq(EmbedSessionEntity::getProjectCode, projectCode)
                        .eq(EmbedSessionEntity::getPageKey, pageKey));
        List<String> embedSessionIds = embedSessions.stream()
                .map(EmbedSessionEntity::getSessionId)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        int deletedPageActionEvents = embedSessionIds.isEmpty()
                ? 0
                : pageActionEventMapper.delete(Wrappers.<PageActionEventEntity>lambdaQuery()
                        .in(PageActionEventEntity::getSessionId, embedSessionIds));
        int deletedEmbedChatEvents = embedSessionIds.isEmpty()
                ? 0
                : embedChatEventMapper.delete(Wrappers.<EmbedChatEventEntity>lambdaQuery()
                        .in(EmbedChatEventEntity::getSessionId, embedSessionIds));
        int deletedEmbedSessions = embedSessionMapper.delete(Wrappers.<EmbedSessionEntity>lambdaQuery()
                .eq(EmbedSessionEntity::getProjectCode, projectCode)
                .eq(EmbedSessionEntity::getPageKey, pageKey));

        int deletedActions = pageActionRegistryMapper.delete(Wrappers.<PageActionRegistryEntity>lambdaQuery()
                .eq(PageActionRegistryEntity::getProjectCode, projectCode)
                .eq(PageActionRegistryEntity::getPageKey, pageKey));

        List<AiAccessSessionEntity> pageAssistantSessions = aiAccessSessionMapper.selectList(
                Wrappers.<AiAccessSessionEntity>lambdaQuery()
                        .eq(AiAccessSessionEntity::getProjectCode, projectCode)
                        .eq(AiAccessSessionEntity::getScenario, PAGE_ASSISTANT_SCENARIO)
                        .eq(AiAccessSessionEntity::getTargetPageKey, pageKey));
        List<String> accessSessionIds = pageAssistantSessions.stream()
                .map(AiAccessSessionEntity::getSessionId)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        int deletedAccessSteps = accessSessionIds.isEmpty()
                ? 0
                : aiAccessStepMapper.delete(Wrappers.<AiAccessStepEntity>lambdaQuery()
                        .in(AiAccessStepEntity::getSessionId, accessSessionIds));
        int deletedAccessSessions = pageAssistantSessions.isEmpty()
                ? 0
                : aiAccessSessionMapper.delete(Wrappers.<AiAccessSessionEntity>lambdaQuery()
                        .eq(AiAccessSessionEntity::getProjectCode, projectCode)
                        .eq(AiAccessSessionEntity::getScenario, PAGE_ASSISTANT_SCENARIO)
                        .eq(AiAccessSessionEntity::getTargetPageKey, pageKey));

        int deletedPages = pageRegistryMapper.delete(Wrappers.<PageRegistryEntity>lambdaQuery()
                .eq(PageRegistryEntity::getProjectCode, projectCode)
                .eq(PageRegistryEntity::getPageKey, pageKey));

        log.info(
                "Deleted frontend page registry projectCode={}, pageKey={}, pages={}, actions={}, embedSessions={}, pageActionEvents={}, embedChatEvents={}, accessSessions={}, accessSteps={}",
                projectCode,
                pageKey,
                deletedPages,
                deletedActions,
                deletedEmbedSessions,
                deletedPageActionEvents,
                deletedEmbedChatEvents,
                deletedAccessSessions,
                deletedAccessSteps);
        return new PageRegistryDeleteResult(
                pageId,
                projectCode,
                pageKey,
                deletedPages,
                deletedActions,
                deletedEmbedSessions,
                deletedPageActionEvents,
                deletedEmbedChatEvents,
                deletedAccessSessions,
                deletedAccessSteps);
    }

    /**
     * 不删除 ai_agent_workflow_binding / Workflow / Agent 等全局编排资源；
     * 仅清理页面注册目录及其直接关联的运行时会话、动作目录与页面助手接入进度。
     */
    public record PageRegistryDeleteResult(
            Long pageId,
            String projectCode,
            String pageKey,
            int deletedPages,
            int deletedActions,
            int deletedEmbedSessions,
            int deletedPageActionEvents,
            int deletedEmbedChatEvents,
            int deletedAccessSessions,
            int deletedAccessSteps) {
    }
}
