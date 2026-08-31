package com.enterprise.ai.control.governance;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControlToolAclDecisionServiceTest {

    private ControlToolAclMapper mapper;
    private ControlToolAclDecisionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ControlToolAclMapper.class);
        service = new ControlToolAclDecisionService(mapper);
    }

    @Test
    void projectScopedRuleDoesNotAuthorizeAnotherProject() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                rule("ops", 7L, "orders", "TOOL", "orders.search", "ALLOW")));

        assertEquals(ControlToolAclDecisionService.DECISION_ALLOW,
                service.decide(List.of("ops"), 7L, "orders", "TOOL", "orders.search"));
        assertEquals(ControlToolAclDecisionService.DECISION_DENY_NO_MATCH,
                service.decide(List.of("ops"), 8L, "billing", "TOOL", "orders.search"));
    }

    @Test
    void explicitDenyWinsOverGlobalAllow() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                rule("ops", null, null, "ALL", "*", "ALLOW"),
                rule("ops", 7L, "orders", "TOOL", "orders.search", "DENY")));

        assertEquals(ControlToolAclDecisionService.DECISION_DENY_EXPLICIT,
                service.decide(List.of("ops"), 7L, "orders", "TOOL", "orders.search"));
    }

    @Test
    void managementExplainDoesNotTreatProjectRuleAsGlobal() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                rule("ops", 7L, "orders", "TOOL", "orders.search", "ALLOW")));

        Map<String, String> decisions = service.decideAll(
                List.of("ops"), null, null,
                List.of(new ControlToolAclDecisionService.Target("TOOL", "orders.search")));

        assertEquals(ControlToolAclDecisionService.DECISION_DENY_NO_MATCH,
                decisions.get("orders.search"));
    }

    private ControlToolAclEntity rule(String role, Long projectId, String projectCode,
                                      String kind, String name, String permission) {
        ControlToolAclEntity entity = new ControlToolAclEntity();
        entity.setRoleCode(role);
        entity.setProjectId(projectId);
        entity.setProjectCode(projectCode);
        entity.setTargetKind(kind);
        entity.setTargetName(name);
        entity.setPermission(permission);
        entity.setEnabled(true);
        return entity;
    }
}
