package com.enterprise.ai.agent.acl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link ToolAclService#decide} 行为覆盖：
 * 1. roles 为空 → SKIPPED
 * 2. DENY 优先于 ALLOW
 * 3. 通配 target_name='*' / target_kind='ALL'
 * 4. 无命中 → 默认拒绝
 * 5. TOOL vs SKILL 区分
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class ToolAclServiceTest {

    private ToolAclMapper mapper;
    private ToolAclService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ToolAclMapper.class);
        service = new ToolAclService(mapper);
    }

    private ToolAclEntity rule(String role, String kind, String target, String perm) {
        ToolAclEntity e = new ToolAclEntity();
        e.setId((long) (Math.random() * 1_000_000));
        e.setRoleCode(role);
        e.setTargetKind(kind);
        e.setTargetName(target);
        e.setPermission(perm);
        e.setEnabled(Boolean.TRUE);
        return e;
    }

    private void stubRulesForRole(String role, List<ToolAclEntity> rules) {
        // 本测试不关心具体的查询条件，只要同 role 返回固定列表即可
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(inv -> rules);
    }

    @Test
    void roles_empty_returns_skipped() {
        assertEquals(ToolAclDecision.SKIPPED, service.decide(null, "any", false));
        assertEquals(ToolAclDecision.SKIPPED, service.decide(List.of(), "any", false));
    }

    @Test
    void no_match_denies() {
        stubRulesForRole("ops", List.of(rule("ops", "TOOL", "list_order", "ALLOW")));
        assertEquals(ToolAclDecision.DENY_NO_MATCH,
                service.decide(List.of("ops"), "delete_order", false));
    }

    @Test
    void explicit_deny_beats_allow() {
        stubRulesForRole("ops", List.of(
                rule("ops", "TOOL", "*", "ALLOW"),
                rule("ops", "TOOL", "delete_order", "DENY")));
        assertEquals(ToolAclDecision.DENY_EXPLICIT,
                service.decide(List.of("ops"), "delete_order", false));
        // 同 roles 下，另一个 tool 仍走 ALLOW 通配
        // 这里需要重新 evict 缓存，否则会拿到上一次的决策，但本测试 mapper mock 是全集
        service.evictCache();
        assertEquals(ToolAclDecision.ALLOW,
                service.decide(List.of("ops"), "list_order", false));
    }

    @Test
    void wildcard_target_all_kind() {
        stubRulesForRole("admin", List.of(rule("admin", "ALL", "*", "ALLOW")));
        assertEquals(ToolAclDecision.ALLOW,
                service.decide(List.of("admin"), "anything", false));
        service.evictCache();
        assertEquals(ToolAclDecision.ALLOW,
                service.decide(List.of("admin"), "some_skill", true));
    }

    @Test
    void tool_rule_does_not_allow_skill() {
        stubRulesForRole("ops", List.of(rule("ops", "TOOL", "*", "ALLOW")));
        // SKILL 不受 TOOL 规则影响 → DENY_NO_MATCH
        assertEquals(ToolAclDecision.DENY_NO_MATCH,
                service.decide(List.of("ops"), "some_skill", true));
    }

    @Test
    void disabled_rules_ignored() {
        ToolAclEntity disabled = rule("ops", "TOOL", "delete_order", "DENY");
        disabled.setEnabled(Boolean.FALSE);
        ToolAclEntity active = rule("ops", "TOOL", "*", "ALLOW");
        stubRulesForRole("ops", List.of(disabled, active));
        assertEquals(ToolAclDecision.ALLOW,
                service.decide(List.of("ops"), "delete_order", false));
    }

    @Test
    void multi_role_any_allow() {
        // 第一个 role 没匹配，第二个 role 命中 ALLOW
        ToolAclEntity opsRule = rule("ops", "TOOL", "list_order", "ALLOW");
        ToolAclEntity adminRule = rule("admin", "TOOL", "delete_order", "ALLOW");
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(
                List.of(opsRule), List.of(adminRule));
        assertEquals(ToolAclDecision.ALLOW,
                service.decide(List.of("ops", "admin"), "delete_order", false));
    }

    @Test
    void multi_role_deny_beats_allow_across_roles() {
        ToolAclEntity opsRule = rule("ops", "TOOL", "*", "ALLOW");
        ToolAclEntity customerRule = rule("customer", "TOOL", "delete_order", "DENY");
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(
                List.of(opsRule), List.of(customerRule));
        // 尽管 ops 允许，只要 customer 明确禁止，整体必须 DENY_EXPLICIT
        assertEquals(ToolAclDecision.DENY_EXPLICIT,
                service.decide(List.of("ops", "customer"), "delete_order", false));
    }

    @Test
    void explain_batch_results() {
        stubRulesForRole("ops", List.of(
                rule("ops", "TOOL", "list_order", "ALLOW"),
                rule("ops", "TOOL", "delete_order", "DENY")));
        List<ToolAclService.ToolAclTargetRef> targets = new ArrayList<>();
        targets.add(new ToolAclService.ToolAclTargetRef("TOOL", "list_order"));
        targets.add(new ToolAclService.ToolAclTargetRef("TOOL", "delete_order"));
        targets.add(new ToolAclService.ToolAclTargetRef("TOOL", "unknown"));
        Map<String, ToolAclDecision> result = service.explain(List.of("ops"), targets);
        assertEquals(ToolAclDecision.ALLOW, result.get("list_order"));
        assertEquals(ToolAclDecision.DENY_EXPLICIT, result.get("delete_order"));
        assertEquals(ToolAclDecision.DENY_NO_MATCH, result.get("unknown"));
    }

    @Test
    void create_normalizes_and_validates() {
        when(mapper.insert(any(ToolAclEntity.class))).thenReturn(1);
        ToolAclEntity input = new ToolAclEntity();
        input.setRoleCode("ops");
        input.setTargetName("delete_order");
        // 省略 kind / permission，看是否默认
        ToolAclEntity saved = service.create(input);
        assertEquals("TOOL", saved.getTargetKind());
        assertEquals("ALLOW", saved.getPermission());
        assertEquals(Boolean.TRUE, saved.getEnabled());
    }

    @Test
    void create_rejects_invalid_kind_or_permission() {
        ToolAclEntity bad = new ToolAclEntity();
        bad.setRoleCode("ops");
        bad.setTargetName("x");
        bad.setTargetKind("SHOUTING");
        assertThrows(IllegalArgumentException.class, () -> service.create(bad));

        ToolAclEntity bad2 = new ToolAclEntity();
        bad2.setRoleCode("ops");
        bad2.setTargetName("x");
        bad2.setPermission("MAYBE");
        assertThrows(IllegalArgumentException.class, () -> service.create(bad2));
    }
}
