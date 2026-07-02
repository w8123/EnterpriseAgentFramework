package com.enterprise.ai.capability.catalog.composition;

import com.enterprise.ai.capability.client.runtime.CapabilityRuntimeTraceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CapabilityCompositionMetricsService {

    private final CapabilityRuntimeTraceClient traceClient;

    public CompositionMetricsView metrics(String compositionName, int days) {
        int safeDays = Math.max(1, Math.min(days, 30));
        List<CapabilityRuntimeTraceClient.ToolCallLogRecord> logs =
                traceClient.listToolCallsByTool(compositionName, safeDays);
        if (logs.isEmpty()) {
            return CompositionMetricsView.empty(safeDays);
        }
        List<Integer> latencies = logs.stream()
                .map(CapabilityRuntimeTraceClient.ToolCallLogRecord::elapsedMs)
                .filter(value -> value != null && value >= 0)
                .toList();
        List<Integer> tokenCosts = logs.stream()
                .map(CapabilityRuntimeTraceClient.ToolCallLogRecord::tokenCost)
                .filter(value -> value != null && value >= 0)
                .toList();
        long successCount = logs.stream()
                .filter(log -> Boolean.TRUE.equals(log.success()))
                .count();
        return new CompositionMetricsView(
                percentile(latencies, 50),
                percentile(latencies, 95),
                percentile(tokenCosts, 50),
                percentile(tokenCosts, 95),
                logs.size(),
                (double) successCount / logs.size(),
                buildDailyTrend(logs)
        );
    }

    private List<CompositionMetricPoint> buildDailyTrend(List<CapabilityRuntimeTraceClient.ToolCallLogRecord> logs) {
        Map<LocalDate, List<CapabilityRuntimeTraceClient.ToolCallLogRecord>> grouped = logs.stream()
                .filter(log -> log.createTime() != null)
                .collect(Collectors.groupingBy(log -> log.createTime().toLocalDate()));
        return grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    List<CapabilityRuntimeTraceClient.ToolCallLogRecord> rows = entry.getValue();
                    List<Integer> latencies = rows.stream()
                            .map(CapabilityRuntimeTraceClient.ToolCallLogRecord::elapsedMs)
                            .filter(value -> value != null && value >= 0)
                            .toList();
                    List<Integer> tokens = rows.stream()
                            .map(CapabilityRuntimeTraceClient.ToolCallLogRecord::tokenCost)
                            .filter(value -> value != null && value >= 0)
                            .toList();
                    long success = rows.stream()
                            .filter(row -> Boolean.TRUE.equals(row.success()))
                            .count();
                    return new CompositionMetricPoint(
                            entry.getKey().toString(),
                            rows.size(),
                            rows.isEmpty() ? 0D : (double) success / rows.size(),
                            percentile(latencies, 95),
                            percentile(tokens, 95)
                    );
                })
                .toList();
    }

    private int percentile(List<Integer> values, int percentile) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = values.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        int index = (int) Math.ceil((percentile / 100D) * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    public record CompositionMetricsView(int p50LatencyMs,
                                         int p95LatencyMs,
                                         int p50TokenCost,
                                         int p95TokenCost,
                                         int callCount,
                                         double successRate,
                                         List<CompositionMetricPoint> trends) {
        static CompositionMetricsView empty(int days) {
            List<CompositionMetricPoint> points = new ArrayList<>();
            for (int i = days - 1; i >= 0; i--) {
                points.add(new CompositionMetricPoint(LocalDate.now().minusDays(i).toString(), 0, 0D, 0, 0));
            }
            return new CompositionMetricsView(0, 0, 0, 0, 0, 0D, points);
        }
    }

    public record CompositionMetricPoint(String day,
                                         int callCount,
                                         double successRate,
                                         int p95LatencyMs,
                                         int p95TokenCost) {
    }
}
