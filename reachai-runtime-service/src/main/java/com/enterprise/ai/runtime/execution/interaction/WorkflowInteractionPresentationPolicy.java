package com.enterprise.ai.runtime.execution.interaction;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared presentation policy for composing an assistant answer with a Workflow uiRequest.
 * The textual answer is always retained as a transport/history fallback; capable clients use
 * {@code presentation.mode} to decide which blocks are visible.
 */
public final class WorkflowInteractionPresentationPolicy {

    public static final String CARD_ONLY = "card_only";
    public static final String TEXT_AND_CARD = "text_and_card";
    public static final String TEXT_ONLY = "text_only";

    private static final Set<String> MODES = Set.of(CARD_ONLY, TEXT_AND_CARD, TEXT_ONLY);

    private WorkflowInteractionPresentationPolicy() {
    }

    public static Map<String, Object> resolve(WorkflowInteractionType type,
                                              Object configured) {
        Map<String, Object> presentation = new LinkedHashMap<>(mapValue(configured));
        String mode = normalizeMode(presentation.get("mode"));
        if (!StringUtils.hasText(mode)) {
            mode = type == WorkflowInteractionType.PRESENT_OUTPUT ? CARD_ONLY : TEXT_AND_CARD;
        }
        presentation.put("mode", mode);
        return presentation;
    }

    public static String normalizeMode(Object raw) {
        if (raw == null) {
            return null;
        }
        String normalized = String.valueOf(raw).trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return MODES.contains(normalized) ? normalized : null;
    }

    public static String mode(Object uiRequest) {
        if (uiRequest instanceof WorkflowInteractionUiRequest request) {
            return modeFromPresentation(request.presentation());
        }
        if (uiRequest instanceof Map<?, ?> request) {
            return modeFromPresentation(request.get("presentation"));
        }
        return TEXT_AND_CARD;
    }

    public static boolean isCardOnly(Object uiRequest) {
        return CARD_ONLY.equals(mode(uiRequest));
    }

    private static String modeFromPresentation(Object raw) {
        Map<String, Object> presentation = mapValue(raw);
        String mode = normalizeMode(presentation.get("mode"));
        return StringUtils.hasText(mode) ? mode : TEXT_AND_CARD;
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return result;
    }
}
