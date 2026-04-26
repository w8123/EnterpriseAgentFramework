package com.enterprise.ai.agent.skill.interactive;

import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.model.ChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 恢复挂起的 InteractiveFormSkill（不经由 ReAct 主循环）。
 */
@Service
@RequiredArgsConstructor
public class InteractiveFormResumeService {

    private final InteractiveFormSkillExecutor interactiveFormSkillExecutor;

    public AgentResult resume(ChatRequest request, String sessionId) {
        return interactiveFormSkillExecutor.resume(
                request.getInteractionId(),
                request.getUiSubmit(),
                request.getUserId(),
                request.getRoles(),
                sessionId
        );
    }
}
