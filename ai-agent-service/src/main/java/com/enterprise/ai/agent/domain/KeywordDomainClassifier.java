package com.enterprise.ai.agent.domain;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 关键词分类器：把 {@code domain_def.keywords_json} 中的关键词作为词典，
 * 对用户输入做 contains 匹配，命中关键词数量 + 长度加权作为 score。
 * <p>
 * 60 秒缓存 keywords，避免每次请求都查 DB。新增 / 编辑领域后调用 {@link #invalidate()} 强制刷新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordDomainClassifier implements DomainClassifier {

    private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {};
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final DomainDefMapper domainDefMapper;
    private final ObjectMapper objectMapper;

    private final AtomicReference<Snapshot> snapshotRef = new AtomicReference<>();

    @Override
    public List<DomainClassification> classify(String userText, int topK) {
        if (userText == null || userText.isBlank()) return List.of();
        Snapshot s = ensureSnapshot();
        if (s.entries.isEmpty()) return List.of();

        Map<String, Double> scoreByDomain = new HashMap<>();
        for (KeywordEntry e : s.entries) {
            for (String kw : e.keywords) {
                if (kw.isEmpty()) continue;
                if (userText.contains(kw)) {
                    scoreByDomain.merge(e.code,
                            (double) Math.max(1, kw.length()) /* 长度加权，"工资条" 比 "工资" 更具体 */,
                            Double::sum);
                }
            }
        }
        if (scoreByDomain.isEmpty()) return List.of();

        List<DomainClassification> sorted = new ArrayList<>();
        scoreByDomain.forEach((k, v) -> sorted.add(new DomainClassification(k, v)));
        sorted.sort(Comparator.comparingDouble(DomainClassification::score).reversed());
        if (topK <= 0) topK = 3;
        return sorted.size() <= topK ? sorted : sorted.subList(0, topK);
    }

    public void invalidate() {
        snapshotRef.set(null);
    }

    private Snapshot ensureSnapshot() {
        Snapshot cur = snapshotRef.get();
        if (cur != null && Duration.between(cur.loadedAt, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cur;
        }
        Snapshot fresh = loadFresh();
        snapshotRef.set(fresh);
        return fresh;
    }

    private Snapshot loadFresh() {
        try {
            LambdaQueryWrapper<DomainDefEntity> w = new LambdaQueryWrapper<>();
            w.eq(DomainDefEntity::getEnabled, true);
            List<DomainDefEntity> rows = domainDefMapper.selectList(w);
            List<KeywordEntry> entries = new ArrayList<>();
            for (DomainDefEntity d : rows) {
                List<String> kws = parseKeywords(d.getKeywordsJson());
                if (kws.isEmpty()) continue;
                entries.add(new KeywordEntry(d.getCode(), kws));
            }
            log.info("[DomainClassifier] 加载领域 {} 条，关键词总数 {}", entries.size(),
                    entries.stream().mapToInt(e -> e.keywords.size()).sum());
            return new Snapshot(entries, Instant.now());
        } catch (Exception ex) {
            log.warn("[DomainClassifier] 加载领域字典失败，使用空快照: {}", ex.toString());
            return new Snapshot(List.of(), Instant.now());
        }
    }

    private List<String> parseKeywords(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> raw = objectMapper.readValue(json, STR_LIST);
            List<String> out = new ArrayList<>();
            for (String s : raw) {
                if (s == null) continue;
                String t = s.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        } catch (Exception ex) {
            log.debug("[DomainClassifier] keywords_json 解析失败: {}", json);
            return List.of();
        }
    }

    private record KeywordEntry(String code, List<String> keywords) {}

    private record Snapshot(List<KeywordEntry> entries, Instant loadedAt) {}
}
