package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.mining.SkillDraftEntity;
import com.enterprise.ai.agent.mining.SkillMiningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skill-mining")
@RequiredArgsConstructor
public class SkillMiningController {

    private final SkillMiningService skillMiningService;

    @GetMapping("/precheck")
    public ResponseEntity<SkillMiningService.PrecheckResult> precheck(
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(skillMiningService.precheck(days));
    }

    @PostMapping("/drafts/generate")
    public ResponseEntity<List<SkillDraftEntity>> generate(@RequestBody GenerateRequest req) {
        return ResponseEntity.ok(skillMiningService.generateDrafts(req.days(), req.minSupport(), req.limit()));
    }

    @GetMapping("/drafts")
    public ResponseEntity<List<SkillDraftEntity>> drafts() {
        return ResponseEntity.ok(skillMiningService.listDrafts());
    }

    @PostMapping("/drafts/{id}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(@PathVariable Long id, @RequestBody StatusRequest req) {
        skillMiningService.markDraftStatus(id, req.status(), req.reviewNote());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/drafts/{id}/publish")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable Long id) {
        skillMiningService.publishDraft(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Phase 3.0 Trace → Skill 一键提取：
     * 运营在 TraceTimeline 上选中某次 Agent 执行（可框选部分工具），点击"抽取为 Skill 草稿"触发。
     * 产出的草稿会落到同一个 {@code skill_draft} 审批流。
     */
    @PostMapping("/drafts/from-trace")
    public ResponseEntity<SkillDraftEntity> fromTrace(@RequestBody ExtractFromTraceRequest req) {
        SkillDraftEntity draft = skillMiningService.extractDraftFromTrace(req.traceId(), req.toolNames());
        return ResponseEntity.ok(draft);
    }

    public record GenerateRequest(int days, int minSupport, int limit) {
        public int days() { return days <= 0 ? 7 : days; }
        public int minSupport() { return minSupport <= 0 ? 3 : minSupport; }
        public int limit() { return limit <= 0 ? 10 : limit; }
    }

    public record StatusRequest(String status, String reviewNote) {}

    public record ExtractFromTraceRequest(String traceId, List<String> toolNames) {}
}
