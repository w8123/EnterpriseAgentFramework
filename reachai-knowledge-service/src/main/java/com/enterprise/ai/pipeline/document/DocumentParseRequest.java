package com.enterprise.ai.pipeline.document;

import lombok.Builder;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
public class DocumentParseRequest {

    private String fileName;
    private String declaredContentType;
    private long fileSize;
    private DocumentContent content;
    private DocumentFormat format;

    @Builder.Default
    private Map<String, Object> options = new LinkedHashMap<>();

    public InputStream openStream() throws IOException {
        if (content == null) {
            throw new IOException("document content is unavailable");
        }
        return content.openStream();
    }

    public static DocumentParseRequest fromMultipartFile(MultipartFile file) {
        return DocumentParseRequest.builder()
                .fileName(file.getOriginalFilename())
                .declaredContentType(file.getContentType())
                .fileSize(file.getSize())
                .content(file::getInputStream)
                .build();
    }
}
