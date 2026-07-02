package com.enterprise.ai.model.util;

import org.slf4j.Logger;

import java.util.List;

/**
 * Embedding 请求调试日志：在 DEBUG 级别输出每条待向量文本的长度与预览（避免 INFO 泄露大量正文）。
 */
public final class EmbeddingDebugLogs {

    /** 单条文本预览最大字符数（超出则截断） */
    private static final int MAX_PREVIEW_CHARS = 500;

    private EmbeddingDebugLogs() {
    }

    public static void logInputTexts(Logger log, String prefix, List<String> texts) {
        if (!log.isDebugEnabled()) {
            return;
        }
        if (texts == null) {
            log.debug("{} texts=null", prefix);
            return;
        }
        if (texts.isEmpty()) {
            log.debug("{} 文本列表为空", prefix);
            return;
        }
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            int len = t == null ? 0 : t.length();
            String preview = previewOne(t);
            log.debug("{} 文本[{}/{}] 长度={}, 预览: {}", prefix, i + 1, texts.size(), len, preview);
        }
    }

    private static String previewOne(String t) {
        if (t == null) {
            return "null";
        }
        String normalized = t.replace("\r\n", "\n").replace('\r', '\n')
                .replace("\n", "\\n");
        if (normalized.length() <= MAX_PREVIEW_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_PREVIEW_CHARS)
                + "... (已截断预览，原文长度=" + t.length() + ")";
    }
}
