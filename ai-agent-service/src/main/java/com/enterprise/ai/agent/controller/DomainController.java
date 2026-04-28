package com.enterprise.ai.agent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.domain.DomainAssignmentEntity;
import com.enterprise.ai.agent.domain.DomainAssignmentService;
import com.enterprise.ai.agent.domain.DomainClassification;
import com.enterprise.ai.agent.domain.DomainClassifier;
import com.enterprise.ai.agent.domain.DomainDefEntity;
import com.enterprise.ai.agent.domain.DomainDefMapper;
import com.enterprise.ai.agent.domain.KeywordDomainClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 领域管理 / 归属挂接 / 分类器测试 / 覆盖度统计。
 */
@RestController
@RequestMapping("/api/domains")
@RequiredArgsConstructor
public class DomainController {

    private final DomainDefMapper defMapper;
    private final DomainAssignmentService assignmentService;
    private final DomainClassifier domainClassifier;

    @GetMapping
    public ResponseEntity<List<DomainDefEntity>> list() {
        return ResponseEntity.ok(defMapper.selectList(
                new LambdaQueryWrapper<DomainDefEntity>().orderByAsc(DomainDefEntity::getCode)));
    }

    @PostMapping
    public ResponseEntity<DomainDefEntity> create(@RequestBody DomainDefEntity body) {
        if (body.getEnabled() == null) body.setEnabled(true);
        if (body.getAgentVisible() == null) body.setAgentVisible(true);
        body.setCreatedAt(LocalDateTime.now());
        defMapper.insert(body);
        invalidateCache();
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DomainDefEntity> update(@PathVariable Long id, @RequestBody DomainDefEntity body) {
        body.setId(id);
        defMapper.updateById(body);
        invalidateCache();
        return ResponseEntity.ok(defMapper.selectById(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        defMapper.deleteById(id);
        invalidateCache();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ===== 挂接 ============================================================
    @GetMapping("/{code}/assignments")
    public ResponseEntity<List<DomainAssignmentEntity>> listAssignments(@PathVariable String code) {
        return ResponseEntity.ok(assignmentService.listByDomain(code));
    }

    @PostMapping("/{code}/assignments")
    public ResponseEntity<Map<String, Object>> grantBatch(@PathVariable String code,
                                                           @RequestBody GrantBatchRequest req) {
        int n = 0;
        if (req != null && req.targets() != null) {
            for (TargetRef t : req.targets()) {
                if (t == null || t.kind() == null || t.name() == null) continue;
                assignmentService.upsert(t.kind(), t.name(), code, t.weight(), "MANUAL");
                n++;
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "count", n));
    }

    @DeleteMapping("/assignments/{id}")
    public ResponseEntity<Map<String, Object>> deleteAssignment(@PathVariable Long id) {
        assignmentService.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ===== 分类器测试 =======================================================
    @PostMapping("/classify")
    public ResponseEntity<Map<String, Object>> classify(@RequestBody ClassifyRequest req) {
        int topK = req.topK() == null || req.topK() <= 0 ? 5 : req.topK();
        List<DomainClassification> hits = domainClassifier.classify(req.text(), topK);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DomainClassification h : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domainCode", h.domainCode());
            m.put("score", h.score());
            DomainDefEntity def = defMapper.selectOne(new LambdaQueryWrapper<DomainDefEntity>()
                    .eq(DomainDefEntity::getCode, h.domainCode()).last("LIMIT 1"));
            if (def != null) m.put("name", def.getName());
            m.put("toolCount", assignmentService.listByDomain(h.domainCode()).size());
            rows.add(m);
        }
        return ResponseEntity.ok(Map.of("text", req.text(), "results", rows));
    }

    // ===== 覆盖度统计 =======================================================
    @GetMapping("/coverage")
    public ResponseEntity<List<Map<String, Object>>> coverage() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DomainDefEntity d : defMapper.selectList(null)) {
            Map<String, Long> kindCount = new HashMap<>();
            for (DomainAssignmentEntity a : assignmentService.listByDomain(d.getCode())) {
                kindCount.merge(a.getTargetKind(), 1L, Long::sum);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("domainCode", d.getCode());
            row.put("name", d.getName());
            row.put("toolCount", kindCount.getOrDefault("TOOL", 0L));
            row.put("skillCount", kindCount.getOrDefault("SKILL", 0L));
            row.put("agentCount", kindCount.getOrDefault("AGENT", 0L));
            row.put("projectCount", kindCount.getOrDefault("PROJECT", 0L));
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    private void invalidateCache() {
        if (domainClassifier instanceof KeywordDomainClassifier kw) {
            kw.invalidate();
        }
    }

    public record GrantBatchRequest(List<TargetRef> targets) {}

    public record TargetRef(String kind, String name, Double weight) {}

    public record ClassifyRequest(String text, Integer topK) {}
}
