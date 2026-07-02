package com.enterprise.ai.capability.catalog.mining;

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
@RequestMapping({"/api/skill-mining", "/api/capability-mining"})
@RequiredArgsConstructor
public class CapabilityMiningController {

    private final CapabilityMiningService miningService;

    @GetMapping("/precheck")
    public ResponseEntity<CapabilityMiningService.PrecheckResult> precheck(
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(miningService.precheck(days));
    }

    @PostMapping("/drafts/generate")
    public ResponseEntity<List<CapabilitySkillDraftEntity>> generate(@RequestBody GenerateRequest request) {
        GenerateRequest safeRequest = request == null ? new GenerateRequest(7, 3, 10) : request;
        return ResponseEntity.ok(miningService.generateDrafts(
                safeRequest.days(),
                safeRequest.minSupport(),
                safeRequest.limit()));
    }

    @GetMapping("/drafts")
    public ResponseEntity<List<CapabilitySkillDraftEntity>> drafts() {
        return ResponseEntity.ok(miningService.listDrafts());
    }

    @PostMapping("/drafts/{id}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        miningService.markDraftStatus(id, request == null ? null : request.status(), request == null ? null : request.reviewNote());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/drafts/{id}/publish")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable Long id) {
        miningService.publishDraft(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/drafts/from-trace")
    public ResponseEntity<CapabilitySkillDraftEntity> fromTrace(@RequestBody ExtractFromTraceRequest request) {
        return ResponseEntity.ok(miningService.extractDraftFromTrace(
                request == null ? null : request.traceId(),
                request == null ? null : request.toolNames()));
    }

    @PostMapping("/drafts/from-canvas")
    public ResponseEntity<CapabilitySkillDraftEntity> fromCanvas(@RequestBody ExtractFromCanvasRequest request) {
        return ResponseEntity.ok(miningService.extractDraftFromCanvas(
                request == null ? null : request.agentName(),
                request == null ? null : request.toolNames(),
                request == null ? null : request.canvasJson()));
    }

    @PostMapping("/demo-traces/generate")
    public ResponseEntity<CapabilityMiningService.DemoTraceResult> generateDemoTraces(@RequestBody DemoTraceRequest request) {
        DemoTraceRequest safeRequest = request == null ? new DemoTraceRequest("order_after_sale", 120, 0.92, 0.08) : request;
        return ResponseEntity.ok(miningService.generateDemoTraces(
                safeRequest.scenario(),
                safeRequest.traceCount(),
                safeRequest.successRate(),
                safeRequest.noiseRate()));
    }

    @PostMapping("/demo-traces/clear")
    public ResponseEntity<Map<String, Object>> clearDemoTraces() {
        return ResponseEntity.ok(Map.of("deleted", miningService.deleteDemoTraces()));
    }

    public record GenerateRequest(int days, int minSupport, int limit) {
        public int days() {
            return days <= 0 ? 7 : days;
        }

        public int minSupport() {
            return minSupport <= 0 ? 3 : minSupport;
        }

        public int limit() {
            return limit <= 0 ? 10 : limit;
        }
    }

    public record StatusRequest(String status, String reviewNote) {
    }

    public record ExtractFromTraceRequest(String traceId, List<String> toolNames) {
    }

    public record ExtractFromCanvasRequest(String agentName, List<String> toolNames, String canvasJson) {
    }

    public record DemoTraceRequest(String scenario, int traceCount, double successRate, double noiseRate) {
        public int traceCount() {
            return traceCount <= 0 ? 120 : traceCount;
        }

        public double successRate() {
            return successRate <= 0 ? 0.92 : successRate;
        }

        public double noiseRate() {
            return noiseRate < 0 ? 0.08 : noiseRate;
        }
    }
}
