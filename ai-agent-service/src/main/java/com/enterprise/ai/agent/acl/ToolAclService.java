package com.enterprise.ai.agent.acl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 3.1 Tool ACL 核心服务。
 * <p>
 * 职责：
 * <ol>
 *   <li>提供 ACL 规则的 CRUD（供管理端页面使用）；</li>
 *   <li>提供 {@link #decide(Collection, String, boolean)} 决策入口，供
 *       {@code AgentFactory.createToolkit} 在装配工具前调用；</li>
 *   <li>5 分钟本地缓存：按 {@code roleCode} 聚合，变更时 {@link #evictCache()}；
 *       这是一个进程内缓存，多实例部署请在 CRUD 端点后同步调用 evict（或在集群化 Phase 再换成消息广播）。</li>
 * </ol>
 *
 * <p>决策规则与 {@code tool_acl_phase3_1.sql} 文档保持一致：
 * <pre>
 *   1. roles 为空 → SKIPPED（打 warn，不拦截）
 *   2. 命中 DENY → DENY_EXPLICIT
 *   3. 命中 ALLOW → ALLOW
 *   4. 没有命中任何规则 → DENY_NO_MATCH（保守拒绝）
 * </pre>
 */
@Slf4j
@Service
public class ToolAclService {

    /** 缓存 TTL；5min 足以在审批流程里体现规则变更，且能挡住 Agent 短时间高并发调用。 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    public static final String KIND_TOOL = "TOOL";
    public static final String KIND_SKILL = "SKILL";
    public static final String KIND_ALL = "ALL";

    public static final String PERM_ALLOW = "ALLOW";
    public static final String PERM_DENY = "DENY";

    public static final String WILDCARD_TARGET = "*";

    private final ToolAclMapper mapper;

    /** 按 role_code 聚合的规则缓存 + 各自过期时间戳。 */
    private final Map<String, RulesCache> cache = new ConcurrentHashMap<>();

    public ToolAclService(ToolAclMapper mapper) {
        this.mapper = mapper;
    }

    // ===== 决策入口 ==========================================================

    /**
     * 核心决策方法。与 SQL 文档里的规则一一对应。
     *
     * @param roles     调用者 role 列表；为空走 SKIPPED
     * @param toolName  tool 或 skill 的 name
     * @param isSkill   是否是 skill（用来判定 target_kind 匹配）
     */
    public ToolAclDecision decide(Collection<String> roles, String toolName, boolean isSkill) {
        if (roles == null || roles.isEmpty()) {
            return ToolAclDecision.SKIPPED;
        }
        if (toolName == null || toolName.isBlank()) {
            return ToolAclDecision.DENY_NO_MATCH;
        }
        boolean hasAllow = false;
        for (String role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            List<ToolAclEntity> rules = loadRulesByRole(role.trim());
            for (ToolAclEntity rule : rules) {
                if (!Boolean.TRUE.equals(rule.getEnabled())) {
                    continue;
                }
                if (!kindMatches(rule.getTargetKind(), isSkill)) {
                    continue;
                }
                if (!targetMatches(rule.getTargetName(), toolName)) {
                    continue;
                }
                if (PERM_DENY.equalsIgnoreCase(rule.getPermission())) {
                    // DENY 优先，直接短路。
                    return ToolAclDecision.DENY_EXPLICIT;
                }
                if (PERM_ALLOW.equalsIgnoreCase(rule.getPermission())) {
                    hasAllow = true;
                    // 先不 return，继续扫描同 roles 是否有更精确的 DENY。
                }
            }
        }
        return hasAllow ? ToolAclDecision.ALLOW : ToolAclDecision.DENY_NO_MATCH;
    }

    private boolean kindMatches(String ruleKind, boolean isSkill) {
        if (ruleKind == null) {
            return false;
        }
        String kind = ruleKind.toUpperCase();
        if (KIND_ALL.equals(kind)) {
            return true;
        }
        return isSkill ? KIND_SKILL.equals(kind) : KIND_TOOL.equals(kind);
    }

    private boolean targetMatches(String ruleTarget, String toolName) {
        if (ruleTarget == null) {
            return false;
        }
        if (WILDCARD_TARGET.equals(ruleTarget)) {
            return true;
        }
        return ruleTarget.equals(toolName);
    }

    // ===== 缓存 ==============================================================

    private List<ToolAclEntity> loadRulesByRole(String role) {
        RulesCache cached = cache.get(role);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expireAt > now) {
            return cached.rules;
        }
        List<ToolAclEntity> rules = mapper.selectList(new LambdaQueryWrapper<ToolAclEntity>()
                .eq(ToolAclEntity::getRoleCode, role)
                .eq(ToolAclEntity::getEnabled, Boolean.TRUE));
        if (rules == null) {
            rules = Collections.emptyList();
        }
        cache.put(role, new RulesCache(rules, now + CACHE_TTL.toMillis()));
        return rules;
    }

    /** 变更后调用：清空所有本地缓存。多实例部署请在所有实例上都调一次。 */
    public void evictCache() {
        cache.clear();
    }

    private record RulesCache(List<ToolAclEntity> rules, long expireAt) {}

    // ===== CRUD（管理端）====================================================

    public Page<ToolAclEntity> page(int current, int size, String roleCode, String targetKind) {
        Page<ToolAclEntity> page = new Page<>(Math.max(1, current), Math.max(1, Math.min(200, size)));
        page.addOrder(OrderItem.asc("role_code"));
        page.addOrder(OrderItem.desc("id"));
        LambdaQueryWrapper<ToolAclEntity> query = new LambdaQueryWrapper<>();
        if (roleCode != null && !roleCode.isBlank()) {
            query.eq(ToolAclEntity::getRoleCode, roleCode);
        }
        if (targetKind != null && !targetKind.isBlank()) {
            query.eq(ToolAclEntity::getTargetKind, targetKind.toUpperCase());
        }
        return mapper.selectPage(page, query);
    }

    public List<ToolAclEntity> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<ToolAclEntity>()
                .orderByAsc(ToolAclEntity::getRoleCode)
                .orderByDesc(ToolAclEntity::getId));
    }

    public List<String> listRoles() {
        List<ToolAclEntity> all = listAll();
        List<String> roles = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (ToolAclEntity e : all) {
            if (e.getRoleCode() != null && seen.add(e.getRoleCode())) {
                roles.add(e.getRoleCode());
            }
        }
        return roles;
    }

    public ToolAclEntity create(ToolAclEntity input) {
        normalize(input);
        validate(input);
        input.setCreatedAt(LocalDateTime.now());
        input.setUpdatedAt(LocalDateTime.now());
        mapper.insert(input);
        evictCache();
        log.info("[ToolAcl] create: role={}, target={}:{}, perm={}",
                input.getRoleCode(), input.getTargetKind(), input.getTargetName(), input.getPermission());
        return input;
    }

    public ToolAclEntity update(Long id, ToolAclEntity input) {
        ToolAclEntity existing = mapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("ACL 规则不存在: " + id);
        }
        normalize(input);
        existing.setRoleCode(input.getRoleCode());
        existing.setTargetKind(input.getTargetKind());
        existing.setTargetName(input.getTargetName());
        existing.setPermission(input.getPermission());
        existing.setNote(input.getNote());
        if (input.getEnabled() != null) {
            existing.setEnabled(input.getEnabled());
        }
        validate(existing);
        existing.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(existing);
        evictCache();
        log.info("[ToolAcl] update: id={}, role={}, target={}:{}, perm={}", id,
                existing.getRoleCode(), existing.getTargetKind(), existing.getTargetName(),
                existing.getPermission());
        return existing;
    }

    public void delete(Long id) {
        mapper.deleteById(id);
        evictCache();
        log.info("[ToolAcl] delete: id={}", id);
    }

    public ToolAclEntity toggle(Long id, boolean enabled) {
        ToolAclEntity existing = mapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("ACL 规则不存在: " + id);
        }
        existing.setEnabled(enabled);
        existing.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(existing);
        evictCache();
        return existing;
    }

    /**
     * 批量授权：给同一 role 一次性刷一批 target（用于运营端"一次给 ops 开 10 个 tool"的场景）。
     * 已存在的 {@code (role_code, target_kind, target_name)} 三元组会被更新为最新 permission / note；
     * 本方法仅 upsert，不会删除未在 targets 列表里出现的历史记录。
     */
    public int grantBatch(String roleCode, String permission, List<ToolAclTargetRef> targets, String note) {
        if (roleCode == null || roleCode.isBlank()) {
            throw new IllegalArgumentException("roleCode 不能为空");
        }
        if (targets == null || targets.isEmpty()) {
            return 0;
        }
        String perm = permission == null ? PERM_ALLOW : permission.toUpperCase();
        int n = 0;
        for (ToolAclTargetRef t : targets) {
            if (t == null || t.name() == null || t.name().isBlank()) {
                continue;
            }
            String kind = (t.kind() == null ? KIND_TOOL : t.kind()).toUpperCase();
            ToolAclEntity existing = mapper.selectOne(new LambdaQueryWrapper<ToolAclEntity>()
                    .eq(ToolAclEntity::getRoleCode, roleCode)
                    .eq(ToolAclEntity::getTargetKind, kind)
                    .eq(ToolAclEntity::getTargetName, t.name())
                    .last("limit 1"));
            if (existing == null) {
                ToolAclEntity entity = new ToolAclEntity();
                entity.setRoleCode(roleCode);
                entity.setTargetKind(kind);
                entity.setTargetName(t.name());
                entity.setPermission(perm);
                entity.setNote(note);
                entity.setEnabled(Boolean.TRUE);
                entity.setCreatedAt(LocalDateTime.now());
                entity.setUpdatedAt(LocalDateTime.now());
                mapper.insert(entity);
            } else {
                existing.setPermission(perm);
                if (note != null) {
                    existing.setNote(note);
                }
                existing.setEnabled(Boolean.TRUE);
                existing.setUpdatedAt(LocalDateTime.now());
                mapper.updateById(existing);
            }
            n++;
        }
        evictCache();
        log.info("[ToolAcl] grantBatch: role={}, perm={}, n={}", roleCode, perm, n);
        return n;
    }

    // ===== 诊断 API ==========================================================

    /**
     * 给调试页面用：给定 roles + target 集合，返回 {@code target -> decision} 的映射。
     */
    public Map<String, ToolAclDecision> explain(Collection<String> roles, Collection<ToolAclTargetRef> targets) {
        Map<String, ToolAclDecision> map = new HashMap<>();
        if (targets == null) {
            return map;
        }
        for (ToolAclTargetRef t : targets) {
            if (t == null || t.name() == null) {
                continue;
            }
            boolean isSkill = KIND_SKILL.equalsIgnoreCase(t.kind());
            map.put(t.name(), decide(roles, t.name(), isSkill));
        }
        return map;
    }

    // ===== 内部辅助 ==========================================================

    private void normalize(ToolAclEntity input) {
        if (input.getTargetKind() == null || input.getTargetKind().isBlank()) {
            input.setTargetKind(KIND_TOOL);
        } else {
            input.setTargetKind(input.getTargetKind().toUpperCase());
        }
        if (input.getPermission() == null || input.getPermission().isBlank()) {
            input.setPermission(PERM_ALLOW);
        } else {
            input.setPermission(input.getPermission().toUpperCase());
        }
        if (input.getEnabled() == null) {
            input.setEnabled(Boolean.TRUE);
        }
        if (input.getRoleCode() != null) {
            input.setRoleCode(input.getRoleCode().trim());
        }
        if (input.getTargetName() != null) {
            input.setTargetName(input.getTargetName().trim());
        }
    }

    private void validate(ToolAclEntity input) {
        if (input.getRoleCode() == null || input.getRoleCode().isBlank()) {
            throw new IllegalArgumentException("roleCode 不能为空");
        }
        if (input.getTargetName() == null || input.getTargetName().isBlank()) {
            throw new IllegalArgumentException("targetName 不能为空（通配使用 *）");
        }
        String kind = input.getTargetKind();
        if (!KIND_TOOL.equals(kind) && !KIND_SKILL.equals(kind) && !KIND_ALL.equals(kind)) {
            throw new IllegalArgumentException("targetKind 只支持 TOOL / SKILL / ALL: " + kind);
        }
        String perm = input.getPermission();
        if (!PERM_ALLOW.equals(perm) && !PERM_DENY.equals(perm)) {
            throw new IllegalArgumentException("permission 只支持 ALLOW / DENY: " + perm);
        }
    }

    /**
     * 管理端批量授权请求里的最小 target 结构。与 {@code ToolAclController.GrantBatchRequest}
     * 对应，单独提出来是为了让 service 层不依赖 controller。
     */
    public record ToolAclTargetRef(String kind, String name) {}
}
