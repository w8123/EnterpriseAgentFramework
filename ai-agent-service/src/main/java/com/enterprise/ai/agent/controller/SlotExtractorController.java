package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.skill.interactive.FieldSourceSpec;
import com.enterprise.ai.agent.skill.interactive.FieldSpec;
import com.enterprise.ai.agent.skill.slot.extractor.ExtractContext;
import com.enterprise.ai.agent.skill.slot.extractor.SlotExtractResult;
import com.enterprise.ai.agent.skill.slot.extractor.SlotExtractor;
import com.enterprise.ai.agent.skill.slot.extractor.SlotExtractorRegistry;
import com.enterprise.ai.agent.skill.slot.log.SlotExtractLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SlotExtractor 管理 + 测试台 + 命中率指标接口。
 */
@RestController
@RequestMapping("/api/slot-extractors")
@RequiredArgsConstructor
public class SlotExtractorController {

    private final SlotExtractorRegistry registry;
    private final SlotExtractLogService logService;

    /** 列出已注册提取器（含 metadata），管理端"提取器列表"。 */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SlotExtractor ex : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", ex.name());
            m.put("displayName", ex.displayName());
            m.put("priority", ex.priority());
            m.put("metadata", ex.metadata());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 测试台：给定 userText / 字段定义，对每个适用提取器都跑一次，返回详情，便于运营在前端看哪个能命中。
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> test(@RequestBody TestRequest req) {
        FieldSpec field = FieldSpec.builder()
                .key(req.fieldKey() == null ? "test_field" : req.fieldKey())
                .label(req.fieldLabel() == null ? req.fieldKey() : req.fieldLabel())
                .type(req.fieldType() == null ? "text" : req.fieldType())
                .llmExtractHint(req.llmExtractHint())
                .source(FieldSourceSpec.builder().kind("NONE").build())
                .build();
        ExtractContext ctx = ExtractContext.builder()
                .userId(req.userId())
                .userDeptId(req.userDeptId())
                .now(LocalDateTime.now())
                .sessionVars(Map.of())
                .build();

        List<Map<String, Object>> results = new ArrayList<>();
        for (SlotExtractor ex : registry.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("extractorName", ex.name());
            row.put("displayName", ex.displayName());
            row.put("accepts", false);
            row.put("hit", false);
            try {
                boolean accepts = ex.accepts(field, ctx);
                row.put("accepts", accepts);
                if (accepts) {
                    long t0 = System.currentTimeMillis();
                    Optional<SlotExtractResult> r = ex.extract(req.userText(), field, ctx);
                    row.put("latencyMs", System.currentTimeMillis() - t0);
                    if (r.isPresent()) {
                        row.put("hit", true);
                        row.put("value", r.get().value());
                        row.put("confidence", r.get().confidence());
                        row.put("evidence", r.get().evidence());
                    }
                }
            } catch (Exception ex2) {
                row.put("error", ex2.toString());
            }
            results.add(row);
        }
        Map<String, Object> body = Map.of(
                "field", Map.of("key", field.getKey(), "label", field.getLabel(), "type", field.getType()),
                "userText", req.userText(),
                "results", results
        );
        return ResponseEntity.ok(body);
    }

    /** 按 extractor 维度的命中率 / 平均置信度 / P95 延迟。 */
    @GetMapping("/metrics")
    public ResponseEntity<List<Map<String, Object>>> metrics(@RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(logService.aggregateMetrics(days));
    }

    public record TestRequest(
            String userText,
            String fieldKey,
            String fieldLabel,
            String fieldType,
            String llmExtractHint,
            String userId,
            String userDeptId
    ) {}
}
