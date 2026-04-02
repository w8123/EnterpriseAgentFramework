package com.enterprise.ai.pipeline.step;

import com.enterprise.ai.pipeline.PipelineContext;
import com.enterprise.ai.pipeline.PipelineStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 步骤三：文本清洗 — 去除噪声字符，规范化文本格式。
 *
 * <p>清洗规则：
 * <ul>
 *   <li>去除连续空白（保留单个换行）</li>
 *   <li>去除零宽字符和控制字符</li>
 *   <li>合并连续换行为最多两个</li>
 *   <li>去除首尾空白</li>
 * </ul>
 * 可通过 extraParams 传入自定义正则进行扩展清洗。</p>
 */
@Slf4j
@Component
public class TextCleanStep implements PipelineStep {

    /** 零宽字符和不可见控制字符 */
    private static final Pattern INVISIBLE_CHARS = Pattern.compile("[\\u200B-\\u200F\\u2028-\\u202F\\uFEFF\\u00AD]");

    /** 连续空白行（3个以上换行合并为2个） */
    private static final Pattern MULTI_NEWLINES = Pattern.compile("\\n{3,}");

    /** 行内多余空白 */
    private static final Pattern MULTI_SPACES = Pattern.compile("[^\\S\\n]{2,}");

    @Override
    public void process(PipelineContext context) {
        // 优先使用 OCR 处理后的文本，否则使用原始文本
        String text = context.getOcrText() != null ? context.getOcrText() : context.getRawText();
        if (text == null) {
            text = "";
        }

        // 清洗流程
        text = INVISIBLE_CHARS.matcher(text).replaceAll("");
        text = MULTI_SPACES.matcher(text).replaceAll(" ");
        text = MULTI_NEWLINES.matcher(text).replaceAll("\n\n");
        text = text.trim();

        // 支持自定义清洗正则
        String customPattern = context.getExtraParam("cleanPattern", null);
        if (customPattern != null) {
            text = Pattern.compile(customPattern).matcher(text).replaceAll("");
        }

        context.setCleanedText(text);
        log.debug("TextCleanStep 完成: fileId={}, 清洗后文本长度={}", context.getFileId(), text.length());
    }

    @Override
    public String getName() {
        return "TEXT_CLEAN";
    }
}
