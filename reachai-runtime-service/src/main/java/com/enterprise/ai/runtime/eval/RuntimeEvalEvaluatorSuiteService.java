package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable evaluator-suite versions. Only deterministic server evaluators are accepted in P2. */
@Service
@RequiredArgsConstructor
public class RuntimeEvalEvaluatorSuiteService {

    private static final String DEFAULT_NAME = "ReachAI deterministic core";

    private final RuntimeEvalEvaluatorSuiteVersionMapper mapper;
    private final RuntimeEvalJsonSupport json;

    public List<RuntimeEvalEvaluatorSuiteVersionView> list(String tenantId) {
        return mapper.selectList(Wrappers.<RuntimeEvalEvaluatorSuiteVersionEntity>lambdaQuery()
                        .eq(RuntimeEvalEvaluatorSuiteVersionEntity::getTenantId, normalizedTenant(tenantId))
                        .orderByDesc(RuntimeEvalEvaluatorSuiteVersionEntity::getName)
                        .orderByDesc(RuntimeEvalEvaluatorSuiteVersionEntity::getVersionNo))
                .stream().map(this::view).toList();
    }

    @Transactional
    public RuntimeEvalEvaluatorSuiteVersionView create(Map<String, Object> request) {
        String tenantId = normalizedTenant(text(request, "tenantId"));
        String name = requiredText(request, "name");
        Map<String, Object> config = json.map(request == null ? null : request.get("config"));
        validateConfig(config);
        String fingerprint = json.sha256(json.canonicalJson(config));
        RuntimeEvalEvaluatorSuiteVersionEntity sameContent = mapper.selectOne(
                Wrappers.<RuntimeEvalEvaluatorSuiteVersionEntity>lambdaQuery()
                        .eq(RuntimeEvalEvaluatorSuiteVersionEntity::getTenantId, tenantId)
                        .eq(RuntimeEvalEvaluatorSuiteVersionEntity::getFingerprintSha256, fingerprint)
                        .last("LIMIT 1"));
        if (sameContent != null) return view(sameContent);
        RuntimeEvalEvaluatorSuiteVersionEntity latest = mapper.selectOne(
                Wrappers.<RuntimeEvalEvaluatorSuiteVersionEntity>lambdaQuery()
                        .eq(RuntimeEvalEvaluatorSuiteVersionEntity::getTenantId, tenantId)
                        .eq(RuntimeEvalEvaluatorSuiteVersionEntity::getName, name)
                        .orderByDesc(RuntimeEvalEvaluatorSuiteVersionEntity::getVersionNo)
                        .last("LIMIT 1"));
        int versionNo = latest == null || latest.getVersionNo() == null ? 1 : latest.getVersionNo() + 1;
        return view(insert(tenantId, name, versionNo, config, text(request, "createdBy")));
    }

    RuntimeEvalEvaluatorSuiteVersionEntity resolve(Long id, String tenantId, String actor) {
        if (id != null) {
            RuntimeEvalEvaluatorSuiteVersionEntity value = mapper.selectById(id);
            if (value == null || !normalizedTenant(tenantId).equals(value.getTenantId())) {
                throw new IllegalArgumentException("Eval evaluator suite version not found: " + id);
            }
            validateConfig(json.readMap(value.getConfigJson()));
            return value;
        }
        String tenant = normalizedTenant(tenantId);
        Map<String, Object> config = defaultConfig();
        String fingerprint = json.sha256(json.canonicalJson(config));
        RuntimeEvalEvaluatorSuiteVersionEntity existing = mapper.selectOne(
                Wrappers.<RuntimeEvalEvaluatorSuiteVersionEntity>lambdaQuery()
                        .eq(RuntimeEvalEvaluatorSuiteVersionEntity::getTenantId, tenant)
                        .eq(RuntimeEvalEvaluatorSuiteVersionEntity::getFingerprintSha256, fingerprint)
                        .last("LIMIT 1"));
        if (existing != null) return existing;
        RuntimeEvalEvaluatorSuiteVersionEntity latest = mapper.selectOne(
                Wrappers.<RuntimeEvalEvaluatorSuiteVersionEntity>lambdaQuery()
                        .eq(RuntimeEvalEvaluatorSuiteVersionEntity::getTenantId, tenant)
                        .eq(RuntimeEvalEvaluatorSuiteVersionEntity::getName, DEFAULT_NAME)
                        .orderByDesc(RuntimeEvalEvaluatorSuiteVersionEntity::getVersionNo)
                        .last("LIMIT 1"));
        int versionNo = latest == null || latest.getVersionNo() == null ? 1 : latest.getVersionNo() + 1;
        try {
            return insert(tenant, DEFAULT_NAME, versionNo, config, actor);
        } catch (DuplicateKeyException raced) {
            RuntimeEvalEvaluatorSuiteVersionEntity winner = mapper.selectOne(
                    Wrappers.<RuntimeEvalEvaluatorSuiteVersionEntity>lambdaQuery()
                            .eq(RuntimeEvalEvaluatorSuiteVersionEntity::getTenantId, tenant)
                            .eq(RuntimeEvalEvaluatorSuiteVersionEntity::getFingerprintSha256, fingerprint)
                            .last("LIMIT 1"));
            if (winner == null) throw raced;
            return winner;
        }
    }

    private RuntimeEvalEvaluatorSuiteVersionEntity insert(String tenantId,
                                                           String name,
                                                           int versionNo,
                                                           Map<String, Object> config,
                                                           String actor) {
        String canonical = json.canonicalJson(config);
        RuntimeEvalEvaluatorSuiteVersionEntity entity = new RuntimeEvalEvaluatorSuiteVersionEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setVersionNo(versionNo);
        entity.setStatus("PUBLISHED");
        entity.setConfigJson(canonical);
        entity.setFingerprintSha256(json.sha256(canonical));
        entity.setCreatedBy(actor);
        entity.setCreatedAt(LocalDateTime.now());
        mapper.insert(entity);
        return entity;
    }

    private void validateConfig(Map<String, Object> config) {
        List<Map<String, Object>> evaluators = json.mapList(config.get("evaluators"));
        if (evaluators.isEmpty()) {
            throw new IllegalArgumentException("Eval evaluator suite requires evaluators");
        }
        for (Map<String, Object> evaluator : evaluators) {
            String key = requiredText(evaluator, "key");
            String type = requiredText(evaluator, "type").toUpperCase(Locale.ROOT);
            if (!List.of("RUNTIME_SUCCESS", "DETERMINISTIC_ASSERTIONS", "LATENCY_BUDGET").contains(type)) {
                throw new IllegalArgumentException("Unsupported deterministic evaluator type: " + type);
            }
            double weight = doubleValue(evaluator.get("weight"), 1.0);
            if (weight < 0 || weight > 100) {
                throw new IllegalArgumentException("Eval evaluator weight is invalid: " + key);
            }
        }
        double passThreshold = doubleValue(config.get("passThreshold"), 0.8);
        if (passThreshold < 0 || passThreshold > 1) {
            throw new IllegalArgumentException("Eval passThreshold must be between 0 and 1");
        }
    }

    private Map<String, Object> defaultConfig() {
        return json.ordered(
                "schemaVersion", 1,
                "passThreshold", 0.8,
                "evaluators", List.of(
                        json.ordered("key", "runtime_success", "type", "RUNTIME_SUCCESS",
                                "weight", 0.4, "required", true),
                        json.ordered("key", "assertions", "type", "DETERMINISTIC_ASSERTIONS",
                                "weight", 0.6, "required", true)));
    }

    private RuntimeEvalEvaluatorSuiteVersionView view(RuntimeEvalEvaluatorSuiteVersionEntity value) {
        return new RuntimeEvalEvaluatorSuiteVersionView(
                value.getId(), value.getTenantId(), value.getName(), value.getVersionNo(), value.getStatus(),
                json.readMap(value.getConfigJson()), value.getFingerprintSha256(), value.getCreatedBy(),
                value.getCreatedAt());
    }

    private String requiredText(Map<String, Object> values, String field) {
        String value = text(values, field);
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("Eval evaluator " + field + " is required");
        return value;
    }

    private String text(Map<String, Object> values, String field) {
        Object value = values == null ? null : values.get(field);
        return value == null ? null : String.valueOf(value).trim();
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text && StringUtils.hasText(text)) return Double.parseDouble(text);
        return fallback;
    }

    private String normalizedTenant(String value) {
        return StringUtils.hasText(value) ? value.trim() : "default";
    }
}
