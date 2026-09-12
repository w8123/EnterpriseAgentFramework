package com.enterprise.ai.runtime.trace;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RuntimeToolUsageStatisticsReader implements RuntimeToolUsageStatisticsQuery {
    private final RuntimeToolUsageStatisticsMapper mapper;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Summary summarizeSince(LocalDateTime from) {
        Objects.requireNonNull(from, "from");
        Long ceiling = mapper.maximumUsageId(from);
        if (ceiling == null) return new Summary(0, 0, 0, Map.of(), Map.of());
        // Exact Java keys preserve case, accents and surrounding whitespace independently of DB collation.
        Map<String, Boolean> traces = new HashMap<>();
        Map<String, Long> intents = new LinkedHashMap<>();
        Map<String, Long> agents = new LinkedHashMap<>();
        int logCount = 0;
        long retrievalCount = 0;
        LocalDateTime afterTime = null;
        Long afterId = null;
        while (true) {
            var page = mapper.findUsagePage(from, ceiling, afterTime, afterId, RuntimeToolUsageSql.whitespaceCharacters());
            logCount = Math.addExact(logCount, page.size());
            for (var row : page) {
                if (StringUtils.hasText(row.traceId())) {
                    Boolean previous = traces.putIfAbsent(row.traceId(), row.retrieval());
                    if (row.retrieval() && !Boolean.TRUE.equals(previous)) {
                        traces.put(row.traceId(), true);
                        retrievalCount++;
                    }
                }
                count(intents, row.intentType());
                count(agents, row.agentName());
            }
            if (page.size() < RuntimeToolUsageSql.PAGE_SIZE) break;
            var last = page.get(page.size() - 1);
            afterTime = last.createdAt();
            afterId = last.id();
        }
        return new Summary(logCount, traces.size(), retrievalCount, intents, agents);
    }

    private void count(Map<String, Long> values, String value) {
        if (StringUtils.hasText(value)) values.merge(value, 1L, Math::addExact);
    }
}
