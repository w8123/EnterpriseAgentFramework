package com.enterprise.ai.pipeline.document;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Performs a small, non-destructive probe (every request can reopen its input
 * stream) before routing.  Filename alone is never trusted for binary files.
 */
@Component
@RequiredArgsConstructor
public class DocumentFormatDetector {

    private final DocumentParseProperties properties;

    public DocumentFormat detect(DocumentParseRequest request) {
        if (request == null || request.getFileName() == null || request.getFileName().isBlank()) {
            throw new DocumentParseException(DocumentParseErrorCode.UNSUPPORTED_FORMAT, "文件名不能为空");
        }
        if (request.getFileSize() <= 0) {
            throw new DocumentParseException(DocumentParseErrorCode.EMPTY_CONTENT, "上传文件为空");
        }
        if (request.getFileSize() > properties.getMaxFileBytes()) {
            throw new DocumentParseException(DocumentParseErrorCode.FILE_TOO_LARGE,
                    "文件超过允许大小: " + properties.getMaxFileBytes());
        }

        String extension = extensionOf(request.getFileName());
        DocumentFormat format = DocumentFormat.fromExtension(extension);
        if (format == null) {
            throw new DocumentParseException(DocumentParseErrorCode.UNSUPPORTED_FORMAT,
                    "不支持的文件类型: " + extension);
        }
        ensureDeclaredContentTypeCompatible(request.getDeclaredContentType(), format);

        byte[] header = readHeader(request);
        ensureHeaderMatches(format, header);
        return format;
    }

    private byte[] readHeader(DocumentParseRequest request) {
        try (InputStream input = request.openStream()) {
            return input.readNBytes(properties.getFormatProbeBytes());
        } catch (IOException e) {
            throw new DocumentParseException(DocumentParseErrorCode.INTERNAL_ERROR,
                    "无法读取上传文件", e);
        }
    }

    private void ensureDeclaredContentTypeCompatible(String declaredContentType, DocumentFormat format) {
        if (declaredContentType == null || declaredContentType.isBlank()) {
            return;
        }
        String contentType = declaredContentType.toLowerCase(Locale.ROOT);
        if ("application/octet-stream".equals(contentType)) {
            return;
        }
        boolean text = format.getProviderType() == DocumentProviderType.JAVA_FAST;
        if (contentType.startsWith("text/") && !text) {
            mismatch(format, "声明 MIME 为文本，但文件扩展名不是文本格式");
        }
        if (contentType.startsWith("image/") && !isImage(format)) {
            mismatch(format, "声明 MIME 为图片，但文件扩展名不是图片格式");
        }
        if ("application/pdf".equals(contentType) && format != DocumentFormat.PDF) {
            mismatch(format, "声明 MIME 为 PDF，但文件扩展名不是 PDF");
        }
    }

    private void ensureHeaderMatches(DocumentFormat format, byte[] header) {
        if (header.length == 0) {
            throw new DocumentParseException(DocumentParseErrorCode.EMPTY_CONTENT, "上传文件为空");
        }
        switch (format) {
            case PDF -> require(startsWith(header, "%PDF-".getBytes(StandardCharsets.US_ASCII)), format);
            case DOC -> require(startsWith(header, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                    (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}), format);
            case DOCX, PPTX, XLSX -> require(startsWith(header, new byte[]{'P', 'K'}), format);
            case PNG -> require(startsWith(header, new byte[]{(byte) 0x89, 'P', 'N', 'G'}), format);
            case JPEG -> require(startsWith(header, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}), format);
            case TIFF -> require(startsWith(header, new byte[]{'I', 'I', 0x2A, 0x00})
                    || startsWith(header, new byte[]{'M', 'M', 0x00, 0x2A}), format);
            case BMP -> require(startsWith(header, new byte[]{'B', 'M'}), format);
            case WEBP -> require(startsWith(header, new byte[]{'R', 'I', 'F', 'F'})
                    && header.length >= 12 && header[8] == 'W' && header[9] == 'E'
                    && header[10] == 'B' && header[11] == 'P', format);
            case TXT, MARKDOWN, CSV -> requireNoNulByte(header, format);
            default -> throw new DocumentParseException(DocumentParseErrorCode.UNSUPPORTED_FORMAT,
                    "未定义的文件类型路由: " + format);
        }
    }

    private static boolean startsWith(byte[] source, byte[] prefix) {
        if (source.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (source[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static void requireNoNulByte(byte[] header, DocumentFormat format) {
        for (byte value : header) {
            if (value == 0) {
                mismatch(format, "文本格式包含二进制 NUL 字节");
            }
        }
    }

    private static void require(boolean matched, DocumentFormat format) {
        if (!matched) {
            mismatch(format, "文件扩展名与文件内容不匹配");
        }
    }

    private static void mismatch(DocumentFormat format, String detail) {
        throw new DocumentParseException(DocumentParseErrorCode.FORMAT_MISMATCH,
                detail + ": " + format.getExtension());
    }

    private static String extensionOf(String fileName) {
        String sanitized = fileName.replace('\\', '/');
        int slash = sanitized.lastIndexOf('/');
        String baseName = slash >= 0 ? sanitized.substring(slash + 1) : sanitized;
        int dot = baseName.lastIndexOf('.');
        return dot < 0 || dot == baseName.length() - 1
                ? ""
                : baseName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isImage(DocumentFormat format) {
        return switch (format) {
            case PNG, JPEG, TIFF, BMP, WEBP -> true;
            default -> false;
        };
    }
}
