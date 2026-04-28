package com.enterprise.ai.agent.skill.slot.log;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.skill.slot.extractor.SlotExtractResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SlotExtractor 调用日志写入。异步落库，不阻塞 InteractiveFormSkill 主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotExtractLogService {

    private final SlotExtractLogMapper logMapper;
    private final FieldExtractorBindingMapper bindingMapper;
    private final ObjectMapper objectMapper;

    @Async
    public void recordAsync(String traceId,
                            String skillName,
                            String fieldKey,
                            String extractorName,
                            String userText,
                            Optional<SlotExtractResult> result,
                            long latencyMs) {
        try {
            SlotExtractLogEntity row = new SlotExtractLogEntity();
            row.setTraceId(traceId);
            row.setSkillName(skillName);
            row.setFieldKey(fieldKey);
            row.setExtractorName(extractorName);
            row.setHit(result != null && result.isPresent());
            if (result != null && result.isPresent()) {
                row.setValue(result.get().value() == null ? null : String.valueOf(result.get().value()));
                row.setConfidence(result.get().confidence());
                row.setEvidence(result.get().evidence());
            }
            row.setUserText(truncate(userText, 4000));
            row.setLatencyMs(latencyMs);
            row.setCreateTime(LocalDateTime.now());
            logMapper.insert(row);
        } catch (Exception ex) {
            log.debug("[SlotExtractLogService] 写日志失败（已忽略）: {}", ex.toString());
        }
    }

    public Page<SlotExtractLogEntity> page(int current, int size, Map<String, Object> filters) {
        LambdaQueryWrapper<SlotExtractLogEntity> w = new LambdaQueryWrapper<>();
        Object ext = filters == null ? null : filters.get("extractorName");
        if (ext instanceof String s && !s.isBlank()) {
            w.eq(SlotExtractLogEntity::getExtractorName, s);
        }
        Object skill = filters == null ? null : filters.get("skillName");
        if (skill instanceof String s && !s.isBlank()) {
            w.eq(SlotExtractLogEntity::getSkillName, s);
        }
        Object hit = filters == null ? null : filters.get("hit");
        if (hit instanceof Boolean b) {
            w.eq(SlotExtractLogEntity::getHit, b);
        }
        Object days = filters == null ? null : filters.get("days");
        if (days instanceof Number n && n.intValue() > 0) {
            w.ge(SlotExtractLogEntity::getCreateTime, LocalDateTime.now().minusDays(n.intValue()));
        }
        w.orderByDesc(SlotExtractLogEntity::getCreateTime);
        return logMapper.selectPage(new Page<>(current, size), w);
    }

    /**
     * 按 extractorName 维度的命中率/平均置信度/P95 延迟统计。
     */
    public List<Map<String, Object>> aggregateMetrics(int days) {
        if (days <= 0) days = 7;
        LambdaQueryWrapper<SlotExtractLogEntity> w = new LambdaQueryWrapper<>();
        w.ge(SlotExtractLogEntity::getCreateTime, LocalDateTime.now().minusDays(days));
        List<SlotExtractLogEntity> rows = logMapper.selectList(w);

        Map<String, List<SlotExtractLogEntity>> grouped = new HashMap<>();
        for (SlotExtractLogEntity r : rows) {
            grouped.computeIfAbsent(r.getExtractorName(), k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<SlotExtractLogEntity>> e : grouped.entrySet()) {
            List<SlotExtractLogEntity> list = e.getValue();
            long total = list.size();
            long hit = list.stream().filter(SlotExtractLogEntity::getHit).count();
            double avgConf = list.stream()
                    .filter(SlotExtractLogEntity::getHit)
                    .mapToDouble(x -> x.getConfidence() == null ? 0d : x.getConfidence())
                    .average().orElse(0d);
            List<Long> sortedLat = list.stream()
                    .map(x -> x.getLatencyMs() == null ? 0L : x.getLatencyMs())
                    .sorted().toList();
            long p95 = sortedLat.isEmpty() ? 0
                    : sortedLat.get(Math.min(sortedLat.size() - 1, (int) Math.ceil(0.95 * sortedLat.size()) - 1));
            Map<String, Object> m = new HashMap<>();
            m.put("extractorName", e.getKey());
            m.put("total", total);
            m.put("hit", hit);
            m.put("hitRate", total == 0 ? 0d : (double) hit / total);
            m.put("avgConfidence", avgConf);
            m.put("p95LatencyMs", p95);
            out.add(m);
        }
        return out;
    }

    /** 解析 (skillName, fieldKey) 的提取器白名单，无配置返回 null。 */
    public List<String> resolveBinding(String skillName, String fieldKey) {
        if (skillName == null || fieldKey == null) return null;
        LambdaQueryWrapper<FieldExtractorBindingEntity> w = new LambdaQueryWrapper<>();
        w.eq(FieldExtractorBindingEntity::getSkillName, skillName)
                .eq(FieldExtractorBindingEntity::getFieldKey, fieldKey).last("LIMIT 1");
        FieldExtractorBindingEntity row = bindingMapper.selectOne(w);
        if (row == null || row.getExtractorNamesJson() == null || row.getExtractorNamesJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(row.getExtractorNamesJson(), new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            log.warn("[SlotExtractLogService] 解析 binding json 失败: {}", row.getExtractorNamesJson());
            return null;
        }
    }

    public void upsertBinding(String skillName, String fieldKey, List<String> extractorNames) {
        if (skillName == null || fieldKey == null) return;
        LambdaQueryWrapper<FieldExtractorBindingEntity> w = new LambdaQueryWrapper<>();
        w.eq(FieldExtractorBindingEntity::getSkillName, skillName)
                .eq(FieldExtractorBindingEntity::getFieldKey, fieldKey).last("LIMIT 1");
        FieldExtractorBindingEntity exist = bindingMapper.selectOne(w);
        String json;
        try {
            json = objectMapper.writeValueAsString(extractorNames == null ? List.of() : extractorNames);
        } catch (Exception ex) {
            json = "[]";
        }
        if (exist == null) {
            FieldExtractorBindingEntity row = new FieldExtractorBindingEntity();
            row.setSkillName(skillName);
            row.setFieldKey(fieldKey);
            row.setExtractorNamesJson(json);
            bindingMapper.insert(row);
        } else {
            exist.setExtractorNamesJson(json);
            bindingMapper.updateById(exist);
        }
    }

    public List<FieldExtractorBindingEntity> listBindings(String skillName) {
        LambdaQueryWrapper<FieldExtractorBindingEntity> w = new LambdaQueryWrapper<>();
        if (skillName != null && !skillName.isBlank()) {
            w.eq(FieldExtractorBindingEntity::getSkillName, skillName);
        }
        return bindingMapper.selectList(w);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }
}
