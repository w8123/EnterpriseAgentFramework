package com.enterprise.ai.pipeline.document;

import java.util.Arrays;
import java.util.Locale;

/**
 * Explicit import allow-list.  Routing must never depend on Spring bean order
 * or silently fall through to a different parser.
 */
public enum DocumentFormat {
    TXT("txt", DocumentProviderType.JAVA_FAST),
    MARKDOWN("md", DocumentProviderType.JAVA_FAST),
    CSV("csv", DocumentProviderType.JAVA_FAST),

    DOC("doc", DocumentProviderType.DOCLING),
    DOCX("docx", DocumentProviderType.DOCLING),
    PDF("pdf", DocumentProviderType.DOCLING),
    PPTX("pptx", DocumentProviderType.DOCLING),
    XLSX("xlsx", DocumentProviderType.DOCLING),
    PNG("png", DocumentProviderType.DOCLING),
    JPEG("jpg", DocumentProviderType.DOCLING),
    TIFF("tiff", DocumentProviderType.DOCLING),
    BMP("bmp", DocumentProviderType.DOCLING),
    WEBP("webp", DocumentProviderType.DOCLING);

    private final String extension;
    private final DocumentProviderType providerType;

    DocumentFormat(String extension, DocumentProviderType providerType) {
        this.extension = extension;
        this.providerType = providerType;
    }

    public String getExtension() {
        return extension;
    }

    public DocumentProviderType getProviderType() {
        return providerType;
    }

    public static DocumentFormat fromExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return null;
        }
        String normalized = extension.toLowerCase(Locale.ROOT);
        if ("markdown".equals(normalized)) {
            return MARKDOWN;
        }
        if ("jpeg".equals(normalized)) {
            return JPEG;
        }
        if ("tif".equals(normalized)) {
            return TIFF;
        }
        return Arrays.stream(values())
                .filter(format -> format.extension.equals(normalized))
                .findFirst()
                .orElse(null);
    }
}
