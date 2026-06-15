package com.enterprise.ai.agent.service;

import com.enterprise.ai.agent.llm.LlmService;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.AgentEntryService;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingEntity;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentService {

    private final LlmService llmService;
    private final AgentEntryService agentEntryService;
    private final AgentWorkflowBindingService bindingService;

    private static final String FALLBACK_INTENT = "GENERAL_CHAT";

    public String recognizeIntent(String userMessage) {
        log.debug("开始意图识别: {}", userMessage);

        try {
            List<IntentCandidate> candidates = enabledIntentCandidates();
            String systemPrompt = buildIntentPrompt(candidates);
            String result = llmService.chat(systemPrompt, userMessage, requireIntentModelInstanceId(candidates));

            String intent = normalizeIntent(result, candidates);
            log.info("意图识别结果: {} -> {}", userMessage, intent);
            return intent;

        } catch (Exception e) {
            log.warn("意图识别失败，使用默认意图: {}", FALLBACK_INTENT, e);
            return FALLBACK_INTENT;
        }
    }

    private String buildIntentPrompt(List<IntentCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个意图识别专家。请分析用户的输入，判断用户意图属于以下哪个类别：\n\n");

        int idx = 1;
        for (IntentCandidate candidate : candidates) {
            sb.append(idx++).append(". ").append(candidate.intentType());
            if (StringUtils.hasText(candidate.description())) {
                sb.append(" - ").append(candidate.description());
            }
            sb.append("\n");
        }

        sb.append("\n请只返回意图类别名称（如 KNOWLEDGE_QA），不要返回其他内容。");
        return sb.toString();
    }

    private String normalizeIntent(String rawIntent, List<IntentCandidate> candidates) {
        if (rawIntent == null || rawIntent.isBlank()) {
            return FALLBACK_INTENT;
        }

        String trimmed = rawIntent.trim().toUpperCase();
        for (IntentCandidate candidate : candidates) {
            if (trimmed.contains(candidate.intentType())) {
                return candidate.intentType();
            }
        }

        return FALLBACK_INTENT;
    }

    private List<IntentCandidate> enabledIntentCandidates() {
        Map<String, IntentCandidate> byIntent = new LinkedHashMap<>();
        for (AgentEntryEntity entry : agentEntryService.listEnabled()) {
            String intentType = agentEntryService.readIntentType(entry);
            if (StringUtils.hasText(intentType)) {
                byIntent.putIfAbsent(intentType, new IntentCandidate(intentType, entry.getDescription(), entry.getModelInstanceId()));
            }
        }
        for (AgentWorkflowBindingEntity binding : bindingService.list(null)) {
            if (!Boolean.TRUE.equals(binding.getEnabled()) || !StringUtils.hasText(binding.getIntentType())) {
                continue;
            }
            String intentType = binding.getIntentType().trim().toUpperCase();
            agentEntryService.findById(binding.getAgentId()).ifPresent(entry -> {
                if (Boolean.TRUE.equals(entry.getEnabled())) {
                    byIntent.putIfAbsent(intentType, new IntentCandidate(intentType, entry.getDescription(), entry.getModelInstanceId()));
                }
            });
        }
        return new ArrayList<>(byIntent.values());
    }

    private String requireIntentModelInstanceId(List<IntentCandidate> candidates) {
        return candidates.stream()
                .map(IntentCandidate::modelInstanceId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .map(String::trim)
                .orElseThrow(() -> new IllegalStateException("modelInstanceId is required for intent recognition"));
    }

    private record IntentCandidate(String intentType, String description, String modelInstanceId) {
    }
}
