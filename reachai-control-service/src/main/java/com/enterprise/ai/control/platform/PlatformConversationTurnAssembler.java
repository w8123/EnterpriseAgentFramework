package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.platform.PlatformConversationViews.ConversationRunView;
import com.enterprise.ai.control.platform.PlatformConversationViews.ConversationTurnView;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把嵌入式对话事件和 Runtime 根运行拼成「轮次」。回答事件带 traceId，直接关联；
 * 没有回答的提问（通常是运行失败）按时间就近匹配同一会话里还没被关联的运行。
 */
final class PlatformConversationTurnAssembler {

    private PlatformConversationTurnAssembler() {
    }

    static List<ConversationTurnView> assemble(List<PlatformEmbedChatEventEntity> events,
                                               List<ConversationRunView> runs) {
        Map<String, ConversationRunView> runsByTrace = new LinkedHashMap<>();
        for (ConversationRunView run : runs == null ? List.<ConversationRunView>of() : runs) {
            if (run != null && StringUtils.hasText(run.traceId())) runsByTrace.putIfAbsent(run.traceId(), run);
        }
        List<MutableTurn> turns = new ArrayList<>();
        MutableTurn current = null;
        for (PlatformEmbedChatEventEntity event : events == null ? List.<PlatformEmbedChatEventEntity>of() : events) {
            if (!"MESSAGE".equalsIgnoreCase(event.getEventType())) continue;
            if ("user".equalsIgnoreCase(event.getRole())) {
                current = new MutableTurn();
                current.question = event.getContent();
                current.askedAt = event.getCreatedAt();
                turns.add(current);
            } else if ("assistant".equalsIgnoreCase(event.getRole())) {
                if (current == null || current.answer != null) {
                    current = new MutableTurn();
                    turns.add(current);
                }
                current.answer = event.getContent();
                current.answeredAt = event.getCreatedAt();
                if (StringUtils.hasText(event.getTraceId())) {
                    current.run = runsByTrace.remove(event.getTraceId().trim());
                    if (current.run == null) current.run = traceOnly(event.getTraceId().trim());
                }
            }
        }
        List<ConversationRunView> unmatched = new ArrayList<>(runsByTrace.values());
        unmatched.sort(Comparator.comparing(run -> parse(run.startedAt()),
                Comparator.nullsLast(Comparator.naturalOrder())));
        for (int index = 0; index < turns.size(); index++) {
            MutableTurn turn = turns.get(index);
            if (turn.run != null || turn.askedAt == null) continue;
            LocalDateTime nextAskedAt = index + 1 < turns.size() ? turns.get(index + 1).askedAt : null;
            for (ConversationRunView run : unmatched) {
                LocalDateTime startedAt = parse(run.startedAt());
                if (startedAt == null || startedAt.isBefore(turn.askedAt.minusSeconds(5))) continue;
                if (nextAskedAt != null && !startedAt.isBefore(nextAskedAt)) break;
                turn.run = run;
                unmatched.remove(run);
                break;
            }
        }
        for (ConversationRunView run : unmatched) {
            MutableTurn orphan = new MutableTurn();
            orphan.run = run;
            orphan.askedAt = parse(run.startedAt());
            turns.add(orphan);
        }
        turns.sort(Comparator.comparing(turn -> turn.askedAt != null ? turn.askedAt : turn.answeredAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        List<ConversationTurnView> views = new ArrayList<>(turns.size());
        for (int index = 0; index < turns.size(); index++) {
            MutableTurn turn = turns.get(index);
            views.add(new ConversationTurnView(index + 1, turn.question, turn.askedAt,
                    turn.answer, turn.answeredAt, turn.run));
        }
        return views;
    }

    private static ConversationRunView traceOnly(String traceId) {
        return new ConversationRunView(traceId, null, null, null, null, null, null);
    }

    static LocalDateTime parse(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return LocalDateTime.parse(value.trim().replace(' ', 'T'));
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static final class MutableTurn {
        private String question;
        private LocalDateTime askedAt;
        private String answer;
        private LocalDateTime answeredAt;
        private ConversationRunView run;
    }
}
