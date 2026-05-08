package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.market.MarketItemEntity;
import com.enterprise.ai.agent.market.MarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;

    @GetMapping("/items")
    public ResponseEntity<?> list(@RequestParam(required = false) String assetKind,
                                  @RequestParam(required = false) String status) {
        return ResponseEntity.ok(marketService.list(assetKind, status));
    }

    @PostMapping("/agents/{agentId}/submit")
    public ResponseEntity<?> submitAgent(@PathVariable String agentId, @RequestBody MarketSubmitRequest request) {
        try {
            MarketItemEntity item = marketService.submitAgent(agentId,
                    request == null ? null : request.version(),
                    request == null ? null : request.operator());
            return ResponseEntity.ok(item);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/skills/submit")
    public ResponseEntity<?> submitSkill(@RequestBody MarketSubmitRequest request) {
        try {
            MarketItemEntity item = marketService.submitSkill(request == null ? null : request.qualifiedName(),
                    request == null ? null : request.version(),
                    request == null ? null : request.operator());
            return ResponseEntity.ok(item);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/items/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id, @RequestBody MarketApproveRequest request) {
        try {
            return ResponseEntity.ok(marketService.approve(id, request == null ? null : request.operator()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/items/{id}/dependency-check")
    public ResponseEntity<?> dependencyCheck(@PathVariable Long id) {
        return ResponseEntity.ok(marketService.checkDependencies(id));
    }

    @GetMapping("/items/{id}/export")
    public ResponseEntity<?> exportPackage(@PathVariable Long id) {
        return ResponseEntity.ok(marketService.exportPackage(id));
    }

    public record MarketSubmitRequest(String qualifiedName, String version, String operator) {
    }

    public record MarketApproveRequest(String operator) {
    }

    public record ErrorResponse(String message) {
    }
}
