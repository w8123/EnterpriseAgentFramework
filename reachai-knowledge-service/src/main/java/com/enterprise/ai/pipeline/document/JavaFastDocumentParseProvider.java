package com.enterprise.ai.pipeline.document;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** UTF-8-only fast parser for TXT, Markdown and ordinary CSV. */
@Component
@RequiredArgsConstructor
public class JavaFastDocumentParseProvider implements DocumentParseProvider {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern CODE_FENCE = Pattern.compile("^ {0,3}(```|~~~)");

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
        List<ParsedDocumentElement> elements = request.getFormat() == DocumentFormat.MARKDOWN
                ? markdownSections(text) : List.of();
        if (elements.isEmpty()) {
            String elementType = request.getFormat() == DocumentFormat.CSV ? "CSV" : "TEXT";
            elements = List.of(ParsedDocumentElement.builder()
                    .order(0)
                    .type(elementType)
                    .text(text)
                    .sourceLocator(DocumentSourceLocator.builder()
                            .lineStart(1)
                            .lineEnd(Math.max(1, lineCount))
                            .build())
                    .metadata(Map.of("charset", "UTF-8"))
                    .build());
        }

        return DocumentParseResult.builder()
                .providerType(DocumentProviderType.JAVA_FAST)
                .providerVersion("java-fast-v1")
                .normalizedText(text)
                .markdown(request.getFormat() == DocumentFormat.MARKDOWN ? text : null)
                .elements(elements)
                .metadata(Map.of("lineCount", lineCount, "characterCount", text.length()))
                .build();
    }

    /**
     * One element per Markdown heading section (heading line included, sectionPath = heading chain),
     * so chunks follow the document structure. Headings inside fenced code are text; sections with
     * no body only contribute to the path. A document without headings yields no sections.
     */
    static List<ParsedDocumentElement> markdownSections(String text) {
        String[] lines = text.split("\\R", -1);
        List<ParsedDocumentElement> sections = new ArrayList<>();
        List<String> path = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String heading = null;
        String sectionPath = null;
        int sectionStart = 1;
        boolean inFence = false;
        boolean sawHeading = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (CODE_FENCE.matcher(line).find()) {
                inFence = !inFence;
            }
            Matcher matcher = inFence ? null : MARKDOWN_HEADING.matcher(line);
            if (matcher != null && matcher.matches()) {
                sawHeading = true;
                addSection(sections, heading, body, sectionPath, sectionStart, index);
                int level = matcher.group(1).length();
                while (!levels.isEmpty() && levels.get(levels.size() - 1) >= level) {
                    levels.remove(levels.size() - 1);
                    path.remove(path.size() - 1);
                }
                levels.add(level);
                path.add(matcher.group(2).trim());
                heading = line.trim();
                sectionPath = String.join(" > ", path);
                sectionStart = index + 1;
                body.setLength(0);
                continue;
            }
            body.append(line).append('\n');
        }
        if (!sawHeading) {
            return List.of();
        }
        addSection(sections, heading, body, sectionPath, sectionStart, lines.length);
        return sections;
    }

    private static void addSection(List<ParsedDocumentElement> sections, String heading, StringBuilder body,
                                   String sectionPath, int lineStart, int lineEnd) {
        String content = body.toString().strip();
        if (content.isEmpty()) {
            return;
        }
        String sectionText = heading == null ? content : heading + "\n" + content;
        sections.add(ParsedDocumentElement.builder()
                .order(sections.size())
                .type(heading == null ? "TEXT" : "SECTION")
                .text(sectionText)
                .sectionPath(sectionPath)
                .sourceLocator(DocumentSourceLocator.builder()
                        .lineStart(lineStart)
                        .lineEnd(Math.max(lineStart, lineEnd))
                        .build())
                .metadata(Map.of("charset", "UTF-8"))
                .build());
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
