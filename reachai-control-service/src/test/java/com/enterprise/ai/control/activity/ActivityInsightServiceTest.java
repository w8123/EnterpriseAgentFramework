package com.enterprise.ai.control.activity;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.control.client.model.ControlModelChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityInsightServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ActivityEventMapper events = mock(ActivityEventMapper.class);
    private final ActivityInsightMapper insights = mock(ActivityInsightMapper.class);
    private final ControlModelChatClient model = mock(ControlModelChatClient.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final ActivityInsightService service = new ActivityInsightService(events, insights, model, json, tx);

    @SuppressWarnings("unchecked")
    private void runTransactionsInline() {
        doAnswer(invocation -> {
            ((Consumer<org.springframework.transaction.TransactionStatus>) invocation.getArgument(0))
                    .accept(new SimpleTransactionStatus());
            return null;
        }).when(tx).executeWithoutResult(any());
    }

    private List<ActivityEventEntity> sample() {
        List<ActivityEventEntity> rows = new ArrayList<>();
        for (int day = 0; day < 2; day++) {
            for (String user : List.of("u1", "u2")) {
                int t = day * 1440;
                rows.add(ActivityInsightMinerTest.event(user, t, "GET /customer/page", "客户列表", false, null));
                rows.add(ActivityInsightMinerTest.event(user, t + 1, "POST /customer/create", "新增客户", true, "客户名称不能为空"));
                rows.add(ActivityInsightMinerTest.event(user, t + 2, "POST /customer/create", "新增客户", false, null));
            }
        }
        return rows;
    }

    @Test
    void generatesRuleTextsWithoutModelAndSavesSuggestions() {
        runTransactionsInline();
        when(events.forMining(eq("p1"), any(), anyInt())).thenReturn(sample());
        ActivityInsightService.Generation generation = service.generate("p1", 30, null, "manager01");
        assertThat(generation.ai()).isFalse();
        assertThat(generation.notice()).contains("没有选择模型");
        ArgumentCaptor<ActivityInsightEntity> saved = ArgumentCaptor.forClass(ActivityInsightEntity.class);
        verify(insights, times(generation.flows() + generation.pitfalls())).insert(saved.capture());
        ActivityInsightEntity pitfall = saved.getAllValues().stream()
                .filter(row -> ActivityInsightEntity.PITFALL.equals(row.getKind())).findFirst().orElseThrow();
        assertThat(pitfall.getTitle()).isEqualTo("「新增客户」容易失败");
        assertThat(pitfall.getAdvice()).contains("客户名称不能为空");
        assertThat(pitfall.getStatus()).isEqualTo(ActivityInsightEntity.SUGGESTED);
        assertThat(pitfall.getGeneratedBy()).isEqualTo("RULE");
    }

    @Test
    void usesModelWordingWhenItAnswersJson() {
        runTransactionsInline();
        when(events.forMining(eq("p1"), any(), anyInt())).thenReturn(sample());
        ControlModelChatClient.ChatResponse answer = new ControlModelChatClient.ChatResponse(
                "好的```json\n{\"items\":[{\"id\":\"P1\",\"title\":\"新增客户常漏填名称\",\"summary\":\"s\",\"advice\":\"先填客户名称\",\"shortcutPrompt\":\"帮我新增客户（填写客户名称）\"}]}\n```",
                "deepseek", "deepseek");
        when(model.chat(any())).thenReturn(ApiResult.ok(answer));
        ActivityInsightService.Generation generation = service.generate("p1", 30, "m-flash", "manager01");
        assertThat(generation.ai()).isTrue();
        ArgumentCaptor<ActivityInsightEntity> saved = ArgumentCaptor.forClass(ActivityInsightEntity.class);
        verify(insights, org.mockito.Mockito.atLeastOnce()).insert(saved.capture());
        ActivityInsightEntity pitfall = saved.getAllValues().stream()
                .filter(row -> ActivityInsightEntity.PITFALL.equals(row.getKind())).findFirst().orElseThrow();
        assertThat(pitfall.getTitle()).isEqualTo("新增客户常漏填名称");
        assertThat(pitfall.getGeneratedBy()).isEqualTo("AI");
        assertThat(pitfall.getModelInstanceId()).isEqualTo("m-flash");
    }

    @Test
    void modelFailureFallsBackToRuleTexts() {
        runTransactionsInline();
        when(events.forMining(eq("p1"), any(), anyInt())).thenReturn(sample());
        when(model.chat(any())).thenThrow(new IllegalStateException("模型不可用"));
        ActivityInsightService.Generation generation = service.generate("p1", 30, "m-flash", "manager01");
        assertThat(generation.ai()).isFalse();
        assertThat(generation.notice()).contains("调用模型失败");
    }

    @Test
    void shortcutsPreferThisPageAndTipsOnlyShowHere() {
        ActivityInsightEntity flowHere = insight(1L, ActivityInsightEntity.FLOW, "[\"/erp/customer\"]", "帮我新增客户");
        ActivityInsightEntity flowElsewhere = insight(2L, ActivityInsightEntity.FLOW, "[\"/erp/stock\"]", "帮我查库存");
        ActivityInsightEntity tipHere = insight(3L, ActivityInsightEntity.PITFALL, "[\"/erp/customer\"]", null);
        tipHere.setAdvice("先填客户名称");
        ActivityInsightEntity tipElsewhere = insight(4L, ActivityInsightEntity.PITFALL, "[\"/erp/stock\"]", null);
        tipElsewhere.setAdvice("x");
        when(insights.selectList(any())).thenReturn(List.of(flowElsewhere, flowHere, tipHere, tipElsewhere));
        ActivityInsightService.Shortcuts result = service.shortcuts("p1", "https://erp/erp/customer/edit/5?x=1");
        assertThat(result.page()).isEqualTo("/erp/customer/edit/{id}");
        assertThat(result.shortcuts()).extracting(ActivityInsightService.Shortcut::id).containsExactly(1L, 2L);
        assertThat(result.shortcuts().get(0).forThisPage()).isTrue();
        assertThat(result.tips()).extracting(ActivityInsightService.Tip::id).containsExactly(3L);
    }

    @Test
    void recommendingAFlowNeedsAPromptAndOtherSystemsCannotBeTouched() {
        ActivityInsightEntity row = insight(9L, ActivityInsightEntity.FLOW, "[]", null);
        row.setStatus(ActivityInsightEntity.SUGGESTED);
        when(insights.selectById(9L)).thenReturn(row);
        assertThatThrownBy(() -> service.update("p1", 9L,
                new ActivityInsightService.InsightCommand("RECOMMENDED", null, null, null), "m"))
                .hasMessageContaining("发给助手的话");
        assertThatThrownBy(() -> service.update("other", 9L,
                new ActivityInsightService.InsightCommand("DISMISSED", null, null, null), "m"))
                .hasMessageContaining("不存在");
    }

    private static ActivityInsightEntity insight(Long id, String kind, String pages, String prompt) {
        ActivityInsightEntity row = new ActivityInsightEntity();
        row.setId(id);
        row.setProjectCode("p1");
        row.setKind(kind);
        row.setTitle("t" + id);
        row.setPagePathsJson(pages);
        row.setShortcutPrompt(prompt);
        row.setStatus(ActivityInsightEntity.RECOMMENDED);
        return row;
    }
}
