package com.enterprise.ai.agent.semantic;

/**
 * 从接口级 Markdown 中抽取与 {@code tool_definition.ai_description} 一致的摘要（与生成 pipeline 同规则）。
 */
public final class SemanticMarkdownUtil {

    private SemanticMarkdownUtil() {
    }

    /**
     * 优先取「## 一句话语义」下首段；否则取全文前 500 字。
     */
    public static String extractToolSummary(String md) {
        if (md == null || md.isBlank()) {
            return null;
        }
        String marker = "## 一句话语义";
        int idx = md.indexOf(marker);
        if (idx < 0) {
            return md.length() > 500 ? md.substring(0, 500) : md;
        }
        String rest = md.substring(idx + marker.length()).trim();
        int nextHeader = rest.indexOf("\n##");
        String section = nextHeader > 0 ? rest.substring(0, nextHeader).trim() : rest.trim();
        return section.isBlank() ? null : section;
    }
}
