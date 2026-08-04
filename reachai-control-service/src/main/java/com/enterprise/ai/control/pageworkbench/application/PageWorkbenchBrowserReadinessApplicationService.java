package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ReadinessItem;
import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService;
import com.enterprise.ai.control.platform.PlatformEmbedE2eEvidenceService.EmbedConversationEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PageWorkbenchBrowserReadinessApplicationService {

    public static final String KEY = "PAGE_BROWSER_E2E_READY";
    private static final String LABEL = "业务页面浏览器链路";

    private final PlatformEmbedE2eEvidenceService embedEvidence;
    private final ObjectMapper objectMapper;

    public ReadinessItem evaluate(
            String projectCode,
            String pageKey,
            LocalDateTime observedAfter) {
        EmbedConversationEvidence observed =
                embedEvidence.latestSuccessfulConversation(
                        projectCode,
                        pageKey,
                        observedAfter);
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("source", "reachai-control-service");
        evidence.put("observationType", "EMBED_PAGE_CONVERSATION");
        evidence.put("projectCode", projectCode);
        evidence.put("pageKey", pageKey);
        if (observedAfter != null) {
            evidence.put("observedAfter", observedAfter.toString());
        }
        if (StringUtils.hasText(observed.evidence())) {
            evidence.put("summary", observed.evidence());
        }
        return new ReadinessItem(
                KEY,
                LABEL,
                observed.passed() ? "PASS" : "PENDING",
                observed.passed()
                        ? observed.message()
                        : observed.message()
                                + " 请在业务系统打开当前页面，通过 ReachAI 入口发送一条真实消息并收到回复后重新验证。",
                objectMapper.valueToTree(evidence));
    }
}
