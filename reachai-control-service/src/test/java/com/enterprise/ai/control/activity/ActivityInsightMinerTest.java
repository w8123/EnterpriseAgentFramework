package com.enterprise.ai.control.activity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityInsightMinerTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 1, 9, 0);

    static ActivityEventEntity event(String user, int minute, String op, String name, boolean failed, String error) {
        ActivityEventEntity e = new ActivityEventEntity();
        e.setUserId(user);
        e.setOccurredAt(BASE.plusMinutes(minute));
        e.setOperationKey(op);
        e.setOperationName(name);
        e.setPagePath("/erp/customer");
        e.setOutcome(failed ? "FAILED" : "SUCCESS");
        e.setErrorMessage(error);
        return e;
    }

    @Test
    void findsFrequentSequencesAcrossPeopleAndSessions() {
        List<ActivityEventEntity> events = new ArrayList<>();
        for (int day = 0; day < 3; day++) {
            int t = day * 24 * 60;
            for (String user : List.of("u1", "u2")) {
                events.add(event(user, t, "GET /customer/page", "客户列表", false, null));
                events.add(event(user, t + 1, "POST /customer/create", "新增客户", false, null));
                events.add(event(user, t + 1, "POST /customer/create", "新增客户", false, null));
                events.add(event(user, t + 2, "POST /sale-order/create", "新增销售订单", false, null));
            }
        }
        ActivityInsightMiner.Result result = ActivityInsightMiner.mine(events, 2);
        assertThat(result.sessions()).isEqualTo(6);
        assertThat(result.flows()).isNotEmpty();
        ActivityInsightMiner.Candidate top = result.flows().get(0);
        assertThat(top.steps()).extracting(ActivityInsightMiner.Step::name)
                .containsExactly("客户列表", "新增客户", "新增销售订单");
        assertThat(top.evidence()).containsEntry("sessions", 6).containsEntry("people", 2);
        // the shorter runs inside the 3-step flow happen equally often, so they are folded into it
        assertThat(result.flows()).noneMatch(flow -> flow.steps().size() == 2);
        assertThat(top.pagePaths()).containsExactly("/erp/customer");
    }

    @Test
    void reportsOperationsThatOftenFailWithRetriesAndReasons() {
        List<ActivityEventEntity> events = new ArrayList<>();
        events.add(event("u1", 0, "POST /customer/create", "新增客户", true, "客户名称不能为空"));
        events.add(event("u1", 1, "POST /customer/create", "新增客户", false, null));
        events.add(event("u2", 0, "POST /customer/create", "新增客户", true, "客户名称不能为空"));
        events.add(event("u3", 0, "POST /customer/create", "新增客户", true, "手机号格式不正确"));
        events.add(event("u3", 5, "GET /customer/page", "客户列表", false, null));
        ActivityInsightMiner.Result result = ActivityInsightMiner.mine(events, 2);
        assertThat(result.pitfalls()).hasSize(1);
        ActivityInsightMiner.Candidate pitfall = result.pitfalls().get(0);
        assertThat(pitfall.evidence()).containsEntry("failed", 3).containsEntry("total", 4)
                .containsEntry("retriedThenSucceeded", 1).containsEntry("gaveUp", 2).containsEntry("people", 3);
        assertThat(pitfall.topErrors()).containsExactly("客户名称不能为空", "手机号格式不正确");
    }

    @Test
    void splitsSessionsAfterThirtyIdleMinutes() {
        List<ActivityEventEntity> events = List.of(
                event("u1", 0, "A", null, false, null),
                event("u1", 31, "B", null, false, null),
                event("u1", 40, "C", null, false, null));
        assertThat(ActivityInsightMiner.mine(events, 2).sessions()).isEqualTo(2);
    }

    @Test
    void keepsSameUserIdInDifferentTenantsApart() {
        ActivityEventEntity a = event("1", 0, "A", null, false, null);
        a.setTenantId("t1");
        ActivityEventEntity b = event("1", 1, "B", null, false, null);
        b.setTenantId("t2");
        assertThat(ActivityInsightMiner.mine(List.of(a, b), 2).sessions()).isEqualTo(2);
    }
}
