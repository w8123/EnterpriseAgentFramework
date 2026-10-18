package com.enterprise.ai.control.platform;

import java.time.LocalDateTime;
import java.util.List;

/** 运行中心「对话」视角的只读视图：嵌入式对话正文来自 Control，运行状态来自 Runtime。 */
public final class PlatformConversationViews {

    private PlatformConversationViews() {
    }

    public record ConversationSummaryView(
            String sessionId,
            String projectCode,
            String agentId,
            String externalUserId,
            String externalUserName,
            String pageKey,
            String route,
            String origin,
            int questionCount,
            String lastQuestion,
            LocalDateTime startedAt,
            LocalDateTime lastActiveAt,
            Integer runCount,
            Integer failedRunCount,
            Integer openRunCount) {
    }

    public record ConversationRunView(
            String traceId,
            String status,
            String suspensionReason,
            String errorCode,
            String errorMessage,
            Integer latencyMs,
            String startedAt) {
    }

    /** 一轮对话：用户提问、回答和对应运行，任一部分都可能缺失（例如运行失败时没有回答）。 */
    public record ConversationTurnView(
            int turnNo,
            String question,
            LocalDateTime askedAt,
            String answer,
            LocalDateTime answeredAt,
            ConversationRunView run) {
    }

    public record ConversationDetailView(
            ConversationSummaryView conversation,
            List<ConversationTurnView> turns,
            boolean runsAvailable) {
    }
}
