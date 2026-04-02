package com.enterprise.ai.pipeline.ocr.impl;

import com.enterprise.ai.pipeline.ocr.OcrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 空实现 OCR 服务 — 直接透传原始文本。
 *
 * <p>在未接入真实 OCR 引擎前使用此默认实现，
 * 后续可通过 {@code @Primary} 或条件装配切换到真实实现。</p>
 */
@Slf4j
@Service
public class NoOpOcrService implements OcrService {

    @Override
    public String process(String rawText, Map<String, Object> context) {
        log.debug("OCR 服务未启用，透传原始文本 (length={})", rawText != null ? rawText.length() : 0);
        return rawText;
    }

    @Override
    public boolean isRequired(Map<String, Object> context) {
        return false;
    }
}
