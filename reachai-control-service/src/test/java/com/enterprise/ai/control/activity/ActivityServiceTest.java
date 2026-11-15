package com.enterprise.ai.control.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ActivityEventMapper events = mock(ActivityEventMapper.class);
    private final ActivitySettingMapper settings = mock(ActivitySettingMapper.class);
    private final ActivityService service = new ActivityService(events, settings);

    private JsonNode body(String eventsJson) throws Exception {
        return json.readTree("{\"sdk\":\"java-starter\",\"events\":" + eventsJson + "}");
    }

    @Test
    void storesSanitizedEventsAndDropsOnesWithoutUser() throws Exception {
        long now = System.currentTimeMillis();
        ActivityService.IngestResult result = service.ingest("yudao-erp", body("[" +
                "{\"operation\":\"POST /erp/customer/create\",\"operationName\":\"新增客户\",\"userId\":\"1\",\"userName\":\"芋艿\"," +
                "\"page\":\"https://erp/erp/customer?id=3\",\"outcome\":\"FAILED\",\"error\":\"手机号 13812345678 已存在\",\"occurredAt\":" + now + "}," +
                "{\"operation\":\"GET /erp/customer/page\",\"keyword\":\"张三 13812345678\",\"userId\":\"1\"}," +
                "{\"operation\":\"GET /x\"}]"));
        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.dropped()).isEqualTo(1);
        ArgumentCaptor<ActivityEventEntity> saved = ArgumentCaptor.forClass(ActivityEventEntity.class);
        verify(events, times(2)).insert(saved.capture());
        ActivityEventEntity failed = saved.getAllValues().get(0);
        assertThat(failed.getOutcome()).isEqualTo("FAILED");
        assertThat(failed.getErrorMessage()).isEqualTo("手机号 138****5678 已存在");
        assertThat(failed.getPagePath()).isEqualTo("/erp/customer");
        assertThat(failed.getUserName()).isEqualTo("芋艿");
        assertThat(failed.getSource()).isEqualTo("java-starter");
        assertThat(saved.getAllValues().get(1).getKeyword()).isEqualTo("张三 138****5678");
    }

    @Test
    void followsSettingsDisabledHiddenNamesAndNoSearchWords() throws Exception {
        ActivitySettingEntity off = new ActivitySettingEntity();
        off.setProjectCode("p1");
        off.setEnabled(false);
        when(settings.selectById("p1")).thenReturn(off);
        assertThat(service.ingest("p1", body("[{\"operation\":\"GET /a\",\"userId\":\"1\"}]")).enabled()).isFalse();
        verify(events, never()).insert(any(ActivityEventEntity.class));

        ActivitySettingEntity hidden = new ActivitySettingEntity();
        hidden.setProjectCode("p2");
        hidden.setEnabled(true);
        hidden.setCaptureSearch(false);
        hidden.setHideUserNames(true);
        hidden.setRetentionDays(30);
        when(settings.selectById("p2")).thenReturn(hidden);
        service.ingest("p2", body("[{\"operation\":\"GET /a\",\"keyword\":\"张三\",\"userId\":\"7\",\"userName\":\"张三\"}]"));
        ArgumentCaptor<ActivityEventEntity> saved = ArgumentCaptor.forClass(ActivityEventEntity.class);
        verify(events).insert(saved.capture());
        assertThat(saved.getValue().getUserId()).startsWith("员工-").doesNotContain("7");
        assertThat(saved.getValue().getUserName()).isNull();
        assertThat(saved.getValue().getKeyword()).isNull();
    }

    @Test
    void rejectsEventsOlderThanRetentionAndClampsFutureTimes() throws Exception {
        ActivityService.Setting setting = service.setting("p3");
        LocalDateTime now = LocalDateTime.now();
        long old = System.currentTimeMillis() - 200L * 24 * 3600 * 1000;
        assertThat(service.toEvent("p3", json.readTree("{\"operation\":\"GET /a\",\"userId\":\"1\",\"occurredAt\":" + old + "}"),
                setting, null, now, now.minusDays(90))).isNull();
        long future = System.currentTimeMillis() + 3600_000;
        assertThat(service.toEvent("p3", json.readTree("{\"operation\":\"GET /a\",\"userId\":\"1\",\"occurredAt\":" + future + "}"),
                setting, null, now, now.minusDays(90)).getOccurredAt()).isEqualTo(now);
    }

    @Test
    void capsBatchSize() throws Exception {
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < ActivityService.MAX_EVENTS_PER_BATCH + 3; i++) {
            if (i > 0) many.append(',');
            many.append("{\"operation\":\"GET /a\",\"userId\":\"1\"}");
        }
        ActivityService.IngestResult result = service.ingest("p4", body(many.append(']').toString()));
        assertThat(result.accepted()).isEqualTo(ActivityService.MAX_EVENTS_PER_BATCH);
        assertThat(result.dropped()).isEqualTo(3);
    }

    @Test
    void reportNormalizesNumbers() {
        when(events.summary(any(), any())).thenReturn(new java.util.HashMap<>(java.util.Map.of(
                "total", 3L, "failures", new java.math.BigDecimal("1"))));
        when(events.topOperations(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        ActivityService.Report report = service.report(500, " p5 ");
        assertThat(report.days()).isEqualTo(90);
        assertThat(report.projectCode()).isEqualTo("p5");
        assertThat(report.summary()).containsEntry("failures", 1L);
        assertThat(report.setting().retentionDays()).isEqualTo(90);
    }
}
