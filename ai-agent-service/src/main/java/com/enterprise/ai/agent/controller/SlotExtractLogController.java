package com.enterprise.ai.agent.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.skill.slot.log.FieldExtractorBindingEntity;
import com.enterprise.ai.agent.skill.slot.log.SlotExtractLogEntity;
import com.enterprise.ai.agent.skill.slot.log.SlotExtractLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用日志查询 + 字段-提取器绑定管理。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SlotExtractLogController {

    private final SlotExtractLogService logService;

    @GetMapping("/slot-extract-logs")
    public ResponseEntity<Page<SlotExtractLogEntity>> page(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String extractorName,
            @RequestParam(required = false) String skillName,
            @RequestParam(required = false) Boolean hit,
            @RequestParam(defaultValue = "7") int days) {
        Map<String, Object> filters = new HashMap<>();
        filters.put("extractorName", extractorName);
        filters.put("skillName", skillName);
        filters.put("hit", hit);
        filters.put("days", days);
        return ResponseEntity.ok(logService.page(current, size, filters));
    }

    @GetMapping("/slot-bindings")
    public ResponseEntity<List<FieldExtractorBindingEntity>> bindings(
            @RequestParam(required = false) String skillName) {
        return ResponseEntity.ok(logService.listBindings(skillName));
    }

    @PostMapping("/slot-bindings")
    public ResponseEntity<Map<String, Object>> upsertBinding(@RequestBody UpsertBindingRequest req) {
        logService.upsertBinding(req.skillName(), req.fieldKey(), req.extractorNames());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    public record UpsertBindingRequest(String skillName, String fieldKey, List<String> extractorNames) {}
}
