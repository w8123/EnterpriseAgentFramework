package com.enterprise.ai.control.mcp.application.usage;

import com.enterprise.ai.control.mcp.application.usage.AiUsageReader.AccountKey;
import com.enterprise.ai.control.mcp.application.usage.AiUsageReader.AccountRow;
import com.enterprise.ai.control.mcp.application.usage.ModelCostSource.PriceRow;
import com.enterprise.ai.control.mcp.application.usage.ModelCostSource.TokenRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 「AI 使用情况 · 费用」: tokens ReachAI itself spent calling models, priced with the prices set in the model center.
 * Employees' own Claude Code / Codex spend is billed to their own tools and is not visible here.
 */
@Service
public class AiCostService {

    private static final Logger log = LoggerFactory.getLogger(AiCostService.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);
    static final int MAX_ROWS = 50;

    /** How a run started, folded into what a manager recognises. */
    static final Map<String, String> CHANNEL_OF_ENTRY = Map.ofEntries(
            Map.entry("EMBED", "EMBED"),
            Map.entry("MCP", "MCP"),
            Map.entry("AUTOMATION", "AUTOMATION"),
            Map.entry("DEBUG", "BUILD"),
            Map.entry("WORKFLOW_STUDIO", "BUILD"),
            Map.entry("STUDIO_READ_ONLY_TRIAL", "BUILD"),
            Map.entry("CONSOLE", "BUILD"),
            Map.entry("EVAL", "BUILD"),
            Map.entry("REPLAY", "BUILD"),
            Map.entry("WORKFLOW_AUTHORING", "BUILD"));
    static final Map<String, String> CHANNEL_LABELS = linked(
            "EMBED", "业务系统里的 AI 对话",
            "MCP", "员工自己的 AI 工具调用流程",
            "AUTOMATION", "自动化任务",
            "BUILD", "搭建、调试与评测",
            "OTHER", "其他");
    static final Map<String, String> UNATTRIBUTED_LABELS = Map.of(
            "AUTOMATION", "自动化任务",
            "BUILD", "搭建与调试",
            "MCP", "员工 AI 工具（未识别账号）",
            "EMBED", "业务系统对话（未识别账号）",
            "OTHER", "其他");

    private final ModelCostSource source;
    private final AiUsageReader reader;

    public AiCostService(ModelCostSource source, AiUsageReader reader) {
        this.source = source;
        this.reader = reader;
    }

    public CostReport report(Integer days, String projectCode, boolean includeRuns) {
        int safeDays = days == null || days <= 0 ? 30 : Math.min(days, 90);
        String project = StringUtils.hasText(projectCode) ? projectCode.trim() : null;
        if (!includeRuns) {
            return CostReport.empty(safeDays, project, "你没有查看运行记录的权限，看不到费用。");
        }
        List<TokenRow> rows;
        try {
            rows = source.tokens(project, safeDays);
        } catch (RuntimeException failure) {
            log.warn("[AiCost] token usage unavailable: {}", failure.getMessage());
            return CostReport.empty(safeDays, project, "暂时读不到模型用量，请稍后再试。");
        }
        Map<String, PriceRow> prices = new HashMap<>();
        String priceNotice = null;
        try {
            source.prices().forEach(price -> prices.put(price.modelInstanceId(), price));
        } catch (RuntimeException failure) {
            log.warn("[AiCost] model prices unavailable: {}", failure.getMessage());
            priceNotice = "暂时读不到模型价格，下面只显示 Token。";
        }

        Set<AccountKey> keys = new LinkedHashSet<>();
        for (TokenRow row : rows) {
            AccountKey key = accountKey(row);
            if (key != null) keys.add(key);
        }
        Map<AccountKey, AccountRow> accounts = new HashMap<>();
        if (!keys.isEmpty()) {
            try {
                reader.accounts(keys).forEach(account ->
                        accounts.put(new AccountKey(account.appId(), account.externalUserId()), account));
            } catch (RuntimeException failure) {
                log.warn("[AiCost] account names unavailable: {}", failure.getMessage());
            }
        }

        Acc total = new Acc("total", "合计");
        Map<LocalDate, Acc> byDay = new TreeMap<>();
        Map<String, Acc> byPerson = new LinkedHashMap<>();
        Map<String, Acc> bySystem = new LinkedHashMap<>();
        Map<String, Acc> byChannel = new LinkedHashMap<>();
        CHANNEL_LABELS.forEach((code, label) -> byChannel.put(code, new Acc(code, label)));
        Map<String, Acc> byModel = new LinkedHashMap<>();
        Set<String> currencies = new HashSet<>();
        Set<String> unpriced = new LinkedHashSet<>();

        for (TokenRow row : rows) {
            PriceRow price = prices.get(row.modelInstanceId());
            BigDecimal cost = cost(price, row.inputTokens(), row.outputTokens());
            if (cost == null) {
                unpriced.add(row.modelInstanceId());
            } else if (price.currency() != null) {
                currencies.add(price.currency());
            }
            String channel = CHANNEL_OF_ENTRY.getOrDefault(row.entryType(), "OTHER");
            total.add(row, cost);
            if (row.day() != null) {
                byDay.computeIfAbsent(row.day(), day -> new Acc(day.toString(), day.toString())).add(row, cost);
            }
            AccountKey key = accountKey(row);
            AccountRow account = key == null ? null : accounts.get(key);
            String personKey;
            String personName;
            if (key == null) {
                personKey = "x:" + channel;
                personName = UNATTRIBUTED_LABELS.getOrDefault(channel, "其他");
            } else if (account != null && account.businessUserId() != null) {
                personKey = "u:" + account.businessUserId();
                personName = StringUtils.hasText(account.name()) ? account.name() : key.externalUserId();
            } else {
                personKey = "a:" + key.appId() + ":" + key.externalUserId();
                personName = account != null && StringUtils.hasText(account.name()) ? account.name()
                        : key.appId() + " 账号 " + key.externalUserId();
            }
            String finalName = personName;
            byPerson.computeIfAbsent(personKey, k -> new Acc(k, finalName)).add(row, cost);
            String system = StringUtils.hasText(row.projectCode()) ? row.projectCode() : "-";
            bySystem.computeIfAbsent(system, k -> new Acc(k, "-".equals(k) ? "不属于某个系统" : k)).add(row, cost);
            byChannel.get(channel).add(row, cost);
            String modelId = StringUtils.hasText(row.modelInstanceId()) ? row.modelInstanceId() : "-";
            byModel.computeIfAbsent(modelId, k -> new Acc(k, modelName(price, k))).add(row, cost);
        }

        List<UnpricedModel> missing = unpriced.stream()
                .map(id -> new UnpricedModel(id, modelName(prices.get(id), id)))
                .toList();
        String currency = currencies.size() == 1 ? currencies.iterator().next()
                : currencies.isEmpty() ? "CNY" : "MIXED";
        String notice = priceNotice;
        if (notice == null && "MIXED".equals(currency)) {
            notice = "模型价格里同时有人民币和美元，合计金额按原币种直接相加，仅供参考。";
        }
        return new CostReport(safeDays, project, currency, total.view(),
                byDay.values().stream().map(Acc::view).toList(),
                top(byPerson), top(bySystem),
                byChannel.values().stream().filter(acc -> acc.calls > 0).map(Acc::view).toList(),
                top(byModel), missing, notice, true);
    }

    private static List<CostLine> top(Map<String, Acc> map) {
        return map.values().stream()
                .sorted(Comparator.comparing((Acc acc) -> acc.cost == null ? BigDecimal.ZERO : acc.cost).reversed()
                        .thenComparing(Comparator.comparingLong(Acc::tokens).reversed()))
                .limit(MAX_ROWS)
                .map(Acc::view)
                .toList();
    }

    static AccountKey accountKey(TokenRow row) {
        if (!StringUtils.hasText(row.externalUserId())) {
            return null;
        }
        String app = StringUtils.hasText(row.appId()) ? row.appId() : row.projectCode();
        return StringUtils.hasText(app) ? new AccountKey(app, row.externalUserId()) : null;
    }

    static BigDecimal cost(PriceRow price, long inputTokens, long outputTokens) {
        if (price == null || (price.inputPricePerMillion() == null && price.outputPricePerMillion() == null)) {
            return null;
        }
        BigDecimal in = price.inputPricePerMillion() == null ? BigDecimal.ZERO : price.inputPricePerMillion();
        BigDecimal out = price.outputPricePerMillion() == null ? BigDecimal.ZERO : price.outputPricePerMillion();
        return in.multiply(BigDecimal.valueOf(inputTokens)).add(out.multiply(BigDecimal.valueOf(outputTokens)))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    private static String modelName(PriceRow price, String fallback) {
        if (price == null) return "-".equals(fallback) ? "未知模型" : "已删除的模型";
        return StringUtils.hasText(price.name()) ? price.name() : price.modelName();
    }

    private static Map<String, String> linked(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    private static final class Acc {
        private final String key;
        private final String name;
        private long calls;
        private long runs;
        private long inputTokens;
        private long outputTokens;
        private BigDecimal cost;
        private boolean partial;

        Acc(String key, String name) {
            this.key = key;
            this.name = name;
        }

        void add(TokenRow row, BigDecimal rowCost) {
            calls += row.calls();
            runs += row.runs();
            inputTokens += row.inputTokens();
            outputTokens += row.outputTokens();
            if (rowCost == null) {
                partial = true;
            } else {
                cost = cost == null ? rowCost : cost.add(rowCost);
            }
        }

        long tokens() {
            return inputTokens + outputTokens;
        }

        CostLine view() {
            return new CostLine(key, name, calls, runs, inputTokens, outputTokens,
                    cost == null ? null : cost.setScale(4, RoundingMode.HALF_UP), partial);
        }
    }

    /** {@code partialCost}: some calls used a model without a price, so cost is lower than the real spend. */
    public record CostLine(String key, String name, long calls, long runs, long inputTokens, long outputTokens,
                           BigDecimal cost, boolean partialCost) {
    }

    public record UnpricedModel(String modelInstanceId, String name) {
    }

    public record CostReport(int days, String projectCode, String currency, CostLine total, List<CostLine> byDay,
                             List<CostLine> people, List<CostLine> systems, List<CostLine> channels,
                             List<CostLine> models, List<UnpricedModel> unpricedModels, String notice,
                             boolean available) {
        static CostReport empty(int days, String projectCode, String notice) {
            return new CostReport(days, projectCode, "CNY",
                    new CostLine("total", "合计", 0, 0, 0, 0, null, false),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), notice, false);
        }
    }
}
