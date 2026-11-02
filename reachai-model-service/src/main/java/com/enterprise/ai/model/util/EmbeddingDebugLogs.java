package com.enterprise.ai.model.util;

import org.slf4j.Logger;

import java.util.List;

/**
 * Embedding 请求调试日志只记录文本长度，不记录知识或用户输入正文。
 */
public final class EmbeddingDebugLogs {

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
            log.debug("{} 文本[{}/{}] 长度={}", prefix, i + 1, texts.size(), len);
        }
    }

}
