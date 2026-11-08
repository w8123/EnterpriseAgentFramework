package com.enterprise.ai.control.mcp.application.usage;

import com.enterprise.ai.control.mcp.application.usage.AiUsageReader.AccountRow;
import com.enterprise.ai.control.mcp.application.usage.ModelCostSource.PriceRow;
import com.enterprise.ai.control.mcp.application.usage.ModelCostSource.TokenRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCostServiceTest {

    private final ModelCostSource source = mock(ModelCostSource.class);
    private final AiUsageReader reader = mock(AiUsageReader.class);
    private final AiCostService service = new AiCostService(source, reader);
    private static final LocalDate DAY = LocalDate.of(2026, 11, 8);

    @Test
    void pricesTokensPerPersonSystemChannelAndModel() {
        when(source.tokens(null, 30)).thenReturn(List.of(
                new TokenRow(DAY, "yudao-erp", "7", "7", "yudao-erp", "EMBED", "flash", 2, 1_000_000, 500_000, 1),
                new TokenRow(DAY, null, null, null, "yudao-erp", "AUTOMATION", "flash", 1, 2_000_000, 0, 1),
                new TokenRow(DAY, null, null, null, null, "WORKFLOW_AUTHORING", "pro", 1, 1000, 1000, 0)));
        when(source.prices()).thenReturn(List.of(
                new PriceRow("flash", "DeepSeek Flash", "deepseek-v4-flash", "ACTIVE",
                        new BigDecimal("1"), new BigDecimal("2"), "CNY"),
                new PriceRow("pro", "DeepSeek Pro", "deepseek-v4-pro", "ACTIVE", null, null, "CNY")));
        when(reader.accounts(any())).thenReturn(List.of(new AccountRow("yudao-erp", "7", 42L, "张三")));

        AiCostService.CostReport report = service.report(null, null, true);

        assertTrue(report.available());
        assertEquals("CNY", report.currency());
        // flash: 1M*1 + 0.5M*2 = 2 (embed) ; 2M*1 = 2 (automation); pro unpriced.
        assertEquals(0, new BigDecimal("4").compareTo(report.total().cost()));
        assertTrue(report.total().partialCost());
        assertEquals("张三", report.people().stream().filter(line -> line.key().equals("u:42")).findFirst()
                .orElseThrow().name());
        assertEquals("自动化任务", report.people().stream().filter(line -> line.key().equals("x:AUTOMATION"))
                .findFirst().orElseThrow().name());
        assertEquals(List.of("pro"), report.unpricedModels().stream().map(AiCostService.UnpricedModel::modelInstanceId).toList());
        assertEquals(List.of("EMBED", "AUTOMATION", "BUILD"), report.channels().stream().map(AiCostService.CostLine::key).toList());
        assertEquals(1, report.byDay().size());
    }

    @Test
    void withoutRunPermissionNothingIsRead() {
        AiCostService.CostReport report = service.report(7, "yudao-erp", false);
        assertFalse(report.available());
        verify(source, never()).tokens(any(), anyInt());
    }

    @Test
    void missingPricesStillShowTokens() {
        when(source.tokens("yudao-erp", 90)).thenReturn(List.of(
                new TokenRow(DAY, "yudao-erp", "1", "1", "yudao-erp", "MCP", "flash", 1, 10, 5, 1)));
        when(source.prices()).thenThrow(new IllegalStateException("down"));

        AiCostService.CostReport report = service.report(365, " yudao-erp ", true);

        assertEquals(90, report.days());
        assertNull(report.total().cost());
        assertEquals(15, report.total().inputTokens() + report.total().outputTokens());
        assertTrue(report.notice().contains("价格"));
    }
}
