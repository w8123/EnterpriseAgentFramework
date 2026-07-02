package com.enterprise.ai.control.market;

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
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class ControlMarketController {

    private final ControlMarketService marketService;
    private final ControlMarketItemMapper marketItemMapper;

    @GetMapping("/items")
    public ResponseEntity<List<ControlMarketItemEntity>> list(@RequestParam(required = false) String assetKind,
                                                              @RequestParam(required = false) String status) {
        return ResponseEntity.ok(marketService.list(assetKind, status));
    }

    @GetMapping("/items/{id}")
    public ResponseEntity<ControlMarketItemEntity> detail(@PathVariable Long id) {
        ControlMarketItemEntity item = marketItemMapper.selectById(id);
        return item == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(item);
    }

    @PostMapping("/agents/{agentId}/submit")
    public ResponseEntity<?> submitAgent(@PathVariable String agentId,
                                         @RequestBody(required = false) MarketSubmitRequest request) {
        try {
            return ResponseEntity.ok(marketService.submitAgent(
                    agentId,
                    request == null ? null : request.version(),
                    request == null ? null : request.operator()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/skills/submit")
    public ResponseEntity<?> submitSkill(@RequestBody(required = false) MarketSubmitRequest request) {
        try {
            return ResponseEntity.ok(marketService.submitSkill(
                    request == null ? null : request.qualifiedName(),
                    request == null ? null : request.version(),
                    request == null ? null : request.operator()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
        }
    }

    @PostMapping("/items/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id,
                                     @RequestBody(required = false) MarketApproveRequest request) {
        try {
            return ResponseEntity.ok(marketService.approve(id, request == null ? null : request.operator()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/items/{id}/dependency-check")
    public ResponseEntity<ControlMarketService.ImportCheckResult> dependencyCheck(@PathVariable Long id) {
        return ResponseEntity.ok(marketService.checkDependencies(id));
    }

    @GetMapping("/items/{id}/export")
    public ResponseEntity<Map<String, Object>> exportPackage(@PathVariable Long id) {
        return ResponseEntity.ok(marketService.exportPackage(id));
    }

    public record MarketSubmitRequest(String qualifiedName, String version, String operator) {
    }

    public record MarketApproveRequest(String operator) {
    }

    public record ErrorResponse(String message) {
    }
}
