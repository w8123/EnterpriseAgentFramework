package com.enterprise.ai.pipeline.document;

import com.enterprise.ai.pipeline.step.ChunkStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownSectionSplitTest {

    @Test
    void eachHeadingSectionBecomesOneElementWithItsHeadingChain() {
        List<ParsedDocumentElement> sections = JavaFastDocumentParseProvider.markdownSections("""
                # ERP 常见问题

                ## 仓库说明
                主仓位于上海。

                ## 退货政策
                电子产品签收后 7 天内可无理由退货。

                ### 例外
                定制商品不退。

                ```
                # 这不是标题
                ```
                """);

        assertEquals(3, sections.size());
        assertEquals("ERP 常见问题 > 仓库说明", sections.get(0).getSectionPath());
        assertEquals("## 仓库说明\n主仓位于上海。", sections.get(0).getText());
        assertEquals("ERP 常见问题 > 退货政策", sections.get(1).getSectionPath());
        assertEquals("ERP 常见问题 > 退货政策 > 例外", sections.get(2).getSectionPath());
        assertTrue(sections.get(2).getText().contains("# 这不是标题"));
        assertEquals(3, sections.get(0).getSourceLocator().getLineStart());
    }

    @Test
    void markdownWithoutHeadingsKeepsTheWholeDocumentPath() {
        assertTrue(JavaFastDocumentParseProvider.markdownSections("只有正文\n第二行").isEmpty());
    }

    @Test
    void consecutiveParagraphsUnderOneHeadingAreChunkedTogether() {
        List<ParsedDocumentElement> groups = ChunkStep.sectionGroups(List.of(
                element(0, "HEADING", "退货政策", "退货政策"),
                element(1, "PARAGRAPH", "7 天无理由", "退货政策"),
                element(2, "TABLE", "| a | b |", "退货政策"),
                element(3, "PARAGRAPH", "采购需审批", "采购审批"),
                element(4, "PARAGRAPH", "无标题段落", null)));

        assertEquals(4, groups.size());
        assertEquals("退货政策\n7 天无理由", groups.get(0).getText());
        assertEquals("TABLE", groups.get(1).getType());
        assertEquals("采购需审批", groups.get(2).getText());
    }

    private static ParsedDocumentElement element(int order, String type, String text, String section) {
        return ParsedDocumentElement.builder().order(order).type(type).text(text).sectionPath(section).build();
    }
}
