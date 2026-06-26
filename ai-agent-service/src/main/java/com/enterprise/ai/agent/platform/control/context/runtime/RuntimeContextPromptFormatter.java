package com.enterprise.ai.agent.platform.control.context.runtime;

import com.enterprise.ai.agent.platform.control.context.ContextItemResponse;
import com.enterprise.ai.agent.platform.control.context.ContextItemType;
import com.enterprise.ai.agent.platform.control.context.ContextPackageResponse;
import com.enterprise.ai.agent.platform.control.context.ContextSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class RuntimeContextPromptFormatter {

    private static final int MAX_ITEM_TEXT_CHARS = 240;
    private static final int MAX_PROMPT_CHARS = 4000;

    private final RuntimeContextProperties properties;

    public String format(ContextPackageResponse pkg) {
        if (pkg == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("[ReachAI Runtime Context]");
        lines.add("Use the following context only if relevant. It may be stale unless marked VERIFIED. "
                + "Current user input and live business data take precedence.");
        lines.add("");

        appendSection(lines, "Preference", filterEnabled(pkg.getUserMemory(), properties.isInjectUserMemory()));
        appendSection(lines, "Page Context", filterEnabled(pkg.getPageContext(), properties.isInjectPageContext()));
        appendSection(lines, "Workflow Context", filterEnabled(pkg.getWorkflowContext(), properties.isInjectWorkflowContext()));
        appendSection(lines, "API Context", filterEnabled(pkg.getApiContext(), properties.isInjectApiContext()));
        appendSection(lines, "Rule", filterEnabled(pkg.getRules(), properties.isInjectRules()));
        appendEvidenceHints(lines, pkg.getEvidenceSummary());

        if (lines.size() <= 3) {
            return null;
        }
        String prompt = String.join("\n", lines);
        if (prompt.length() > MAX_PROMPT_CHARS) {
            return prompt.substring(0, MAX_PROMPT_CHARS) + "\n...[truncated]";
        }
        return prompt;
    }

    private List<ContextSearchResult> filterEnabled(List<ContextSearchResult> hits, boolean enabled) {
        if (!enabled || hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits;
    }

    private void appendSection(List<String> lines, String label, List<ContextSearchResult> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        for (ContextSearchResult hit : hits) {
            if (hit == null || hit.getItem() == null) {
                continue;
            }
            ContextItemResponse item = hit.getItem();
            String body = summarizeItem(item);
            if (!StringUtils.hasText(body)) {
                continue;
            }
            lines.add("- " + label + ": " + body);
            lines.add("  trust=" + nullToDash(item.getTrustLevel())
                    + " confidence=" + nullToDash(item.getConfidence())
                    + " source=" + nullToDash(item.getSourceType()));
        }
    }

    private void appendEvidenceHints(List<String> lines, List<ContextSearchResult> evidenceSummary) {
        if (evidenceSummary == null || evidenceSummary.isEmpty()) {
            return;
        }
        for (ContextSearchResult hit : evidenceSummary) {
            if (hit == null || !StringUtils.hasText(hit.getHitReason())) {
                continue;
            }
            lines.add("- Evidence: " + truncate(hit.getHitReason(), 120));
        }
    }

    private String summarizeItem(ContextItemResponse item) {
        String type = formatItemType(item.getItemType());
        String text = firstText(item.getSummary(), item.getContent(), item.getTitle());
        if (!StringUtils.hasText(text)) {
            return type;
        }
        return type + ": " + truncate(text.trim(), MAX_ITEM_TEXT_CHARS);
    }

    private String formatItemType(String itemType) {
        if (!StringUtils.hasText(itemType)) {
            return "Context";
        }
        try {
            ContextItemType parsed = ContextItemType.valueOf(itemType.trim().toUpperCase(Locale.ROOT));
            return switch (parsed) {
                case PREFERENCE -> "Preference";
                case PAGE_CONTEXT -> "Page Context";
                case WORKFLOW_CONTEXT -> "Workflow Context";
                case API_CONTRACT -> "API Context";
                case RULE -> "Rule";
                default -> parsed.name();
            };
        } catch (IllegalArgumentException ex) {
            return itemType.trim();
        }
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }

    private String nullToDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
