package com.enterprise.ai.pipeline.document;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** UTF-8-only fast parser for TXT, Markdown and ordinary CSV. */
@Component
@RequiredArgsConstructor
public class JavaFastDocumentParseProvider implements DocumentParseProvider {

    private final DocumentParseProperties properties;

    @Override
    public DocumentProviderType getProviderType() {
        return DocumentProviderType.JAVA_FAST;
    }

    @Override
    public DocumentParseResult parse(DocumentParseRequest request) {
        if (request.getFormat() == null || request.getFormat().getProviderType() != DocumentProviderType.JAVA_FAST) {
            throw new DocumentParseException(DocumentParseErrorCode.INTERNAL_ERROR, "Java Fast Provider 收到错误格式");
        }
        if (request.getFileSize() > properties.getMaxJavaFastFileBytes()) {
            throw new DocumentParseException(DocumentParseErrorCode.FILE_TOO_LARGE,
                    "文本文件超过 Java Fast 限制: " + properties.getMaxJavaFastFileBytes());
        }

        String text = decodeUtf8(readBytes(request));
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            throw new DocumentParseException(DocumentParseErrorCode.EMPTY_CONTENT,
                    "文本文件内容为空: " + request.getFileName());
        }

        int lineCount = (int) text.lines().count();
        String elementType = request.getFormat() == DocumentFormat.CSV ? "CSV" : "TEXT";
        ParsedDocumentElement element = ParsedDocumentElement.builder()
                .order(0)
                .type(elementType)
                .text(text)
                .sourceLocator(DocumentSourceLocator.builder()
                        .lineStart(1)
                        .lineEnd(Math.max(1, lineCount))
                        .build())
                .metadata(Map.of("charset", "UTF-8"))
                .build();

        return DocumentParseResult.builder()
                .providerType(DocumentProviderType.JAVA_FAST)
                .providerVersion("java-fast-v1")
                .normalizedText(text)
                .markdown(request.getFormat() == DocumentFormat.MARKDOWN ? text : null)
                .elements(List.of(element))
                .metadata(Map.of("lineCount", lineCount, "characterCount", text.length()))
                .build();
    }

    private static byte[] readBytes(DocumentParseRequest request) {
        try (var input = request.openStream()) {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new DocumentParseException(DocumentParseErrorCode.INTERNAL_ERROR,
                    "无法读取文本文件: " + request.getFileName(), e);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new DocumentParseException(DocumentParseErrorCode.INVALID_TEXT_ENCODING,
                    "Java Fast 仅支持 UTF-8 文本文件", e);
        }
    }
}
