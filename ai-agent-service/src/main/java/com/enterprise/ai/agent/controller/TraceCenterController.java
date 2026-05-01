package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.governance.GuardDecisionLogEntity;
import com.enterprise.ai.agent.governance.GuardDecisionLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/trace-center")
@RequiredArgsConstructor
public class TraceCenterController {

    private final GuardDecisionLogService guardDecisionLogService;

    @GetMapping("/guard-decisions")
    public ResponseEntity<List<GuardDecisionLogDTO>> guardDecisions(
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String decisionType,
            @RequestParam(required = false) String targetKind,
            @RequestParam(required = false) String targetName,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false, defaultValue = "100") Integer limit) {
        var query = new GuardDecisionLogService.SearchQuery(
                traceId, decisionType, targetKind, targetName, decision, from, to, limit);
        List<GuardDecisionLogDTO> rows = guardDecisionLogService.search(query)
                .stream()
                .map(GuardDecisionLogDTO::from)
                .toList();
        return ResponseEntity.ok(rows);
    }

    public record GuardDecisionLogDTO(Long id,
                                      String traceId,
                                      String decisionType,
                                      String targetKind,
                                      String targetName,
                                      String decision,
                                      String reason,
                                      String metadataJson,
                                      LocalDateTime createdAt) {
        static GuardDecisionLogDTO from(GuardDecisionLogEntity entity) {
            return new GuardDecisionLogDTO(
                    entity.getId(),
                    entity.getTraceId(),
                    entity.getDecisionType(),
                    entity.getTargetKind(),
                    entity.getTargetName(),
                    entity.getDecision(),
                    entity.getReason(),
                    entity.getMetadataJson(),
                    entity.getCreatedAt()
            );
        }
    }
}
