package com.enterprise.ai.pipeline.document;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, fail-closed router.  It intentionally has no fallback loop.
 */
@Component
public class DocumentParseRouter {

    private final DocumentFormatDetector formatDetector;
    private final Map<DocumentProviderType, DocumentParseProvider> providers;

    public DocumentParseRouter(DocumentFormatDetector formatDetector, List<DocumentParseProvider> providerBeans) {
        this.formatDetector = formatDetector;
        this.providers = new EnumMap<>(DocumentProviderType.class);
        for (DocumentParseProvider provider : providerBeans) {
            DocumentParseProvider previous = providers.put(provider.getProviderType(), provider);
            if (previous != null) {
                throw new IllegalStateException("重复注册文档解析 Provider: " + provider.getProviderType());
            }
        }
    }

    public DocumentFormat detect(DocumentParseRequest request) {
        return formatDetector.detect(request);
    }

    public DocumentParseResult parse(DocumentParseRequest request) {
        DocumentFormat format = formatDetector.detect(request);
        request.setFormat(format);
        DocumentParseProvider provider = providers.get(format.getProviderType());
        if (provider == null) {
            throw new DocumentParseException(DocumentParseErrorCode.INTERNAL_ERROR,
                    "未配置解析 Provider: " + format.getProviderType());
        }

        DocumentParseResult result = provider.parse(request);
        if (result == null || result.getNormalizedText() == null || result.getNormalizedText().isBlank()) {
            throw new DocumentParseException(DocumentParseErrorCode.EMPTY_CONTENT,
                    "解析结果为空: " + request.getFileName());
        }
        if (result.getProviderType() != format.getProviderType()) {
            throw new DocumentParseException(DocumentParseErrorCode.INTERNAL_ERROR,
                    "解析 Provider 与路由不一致: " + request.getFileName());
        }
        result.setFormat(format);
        return result;
    }
}
