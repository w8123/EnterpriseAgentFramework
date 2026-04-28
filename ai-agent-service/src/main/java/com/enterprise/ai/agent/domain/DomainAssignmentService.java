package com.enterprise.ai.agent.domain;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DomainAssignmentService {

    private final DomainAssignmentMapper mapper;

    /**
     * 给定一组目标，逐个返回它们的 domain code 集合。
     * 用于召回时的"软过滤"：当 {@code scope.domains() ⊓ candidate domains == ∅} 时，
     * 调用方决定是否丢弃该候选。
     *
     * <p>命中规则：</p>
     * <ul>
     *     <li>{@code targetKind} 精确匹配传入值（注意 PROJECT 是 project_id 字符串，TOOL/SKILL 是 name）；</li>
     *     <li>对于一个 (kind, name)，可能挂多个 domain，全部返回。</li>
     * </ul>
     */
    public Map<String, Set<String>> domainsByTargetName(String targetKind, List<String> targetNames) {
        Map<String, Set<String>> out = new HashMap<>();
        if (targetNames == null || targetNames.isEmpty()) return out;
        LambdaQueryWrapper<DomainAssignmentEntity> w = new LambdaQueryWrapper<>();
        w.eq(DomainAssignmentEntity::getTargetKind, targetKind)
                .in(DomainAssignmentEntity::getTargetName, targetNames);
        for (DomainAssignmentEntity e : mapper.selectList(w)) {
            out.computeIfAbsent(e.getTargetName(), k -> new HashSet<>()).add(e.getDomainCode());
        }
        return out;
    }

    public List<DomainAssignmentEntity> listByDomain(String domainCode) {
        LambdaQueryWrapper<DomainAssignmentEntity> w = new LambdaQueryWrapper<>();
        w.eq(DomainAssignmentEntity::getDomainCode, domainCode);
        return mapper.selectList(w);
    }

    public List<DomainAssignmentEntity> listByTarget(String kind, String name) {
        LambdaQueryWrapper<DomainAssignmentEntity> w = new LambdaQueryWrapper<>();
        w.eq(DomainAssignmentEntity::getTargetKind, kind)
                .eq(DomainAssignmentEntity::getTargetName, name);
        return mapper.selectList(w);
    }

    public DomainAssignmentEntity upsert(String kind, String name, String domainCode, Double weight, String source) {
        LambdaQueryWrapper<DomainAssignmentEntity> w = new LambdaQueryWrapper<>();
        w.eq(DomainAssignmentEntity::getTargetKind, kind)
                .eq(DomainAssignmentEntity::getTargetName, name)
                .eq(DomainAssignmentEntity::getDomainCode, domainCode).last("LIMIT 1");
        DomainAssignmentEntity exist = mapper.selectOne(w);
        if (exist != null) {
            if (weight != null) exist.setWeight(weight);
            if (source != null) exist.setSource(source);
            mapper.updateById(exist);
            return exist;
        }
        DomainAssignmentEntity row = new DomainAssignmentEntity();
        row.setTargetKind(kind);
        row.setTargetName(name);
        row.setDomainCode(domainCode);
        row.setWeight(weight == null ? 1.0 : weight);
        row.setSource(source == null ? "MANUAL" : source);
        mapper.insert(row);
        return row;
    }

    public int deleteByTargetAndDomain(String kind, String name, String domainCode) {
        LambdaQueryWrapper<DomainAssignmentEntity> w = new LambdaQueryWrapper<>();
        w.eq(DomainAssignmentEntity::getTargetKind, kind)
                .eq(DomainAssignmentEntity::getTargetName, name)
                .eq(DomainAssignmentEntity::getDomainCode, domainCode);
        return mapper.delete(w);
    }

    public int deleteById(Long id) {
        return mapper.deleteById(id);
    }
}
