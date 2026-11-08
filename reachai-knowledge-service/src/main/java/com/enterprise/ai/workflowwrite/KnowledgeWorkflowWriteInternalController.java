package com.enterprise.ai.workflowwrite;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.domain.dto.KnowledgeImportRequest;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.repository.KnowledgeBaseLookup;
import com.enterprise.ai.service.KnowledgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Runtime workflow KNOWLEDGE_WRITE: appends one text document to a knowledge base of the same project.
 * Authenticated by {@link KnowledgeWorkflowWriteAuthFilter}. The file id is derived from the invocation key.
 * An interrupted replacement has an unknown outcome and must be investigated before any retry.
 */
@RestController
@RequestMapping(KnowledgeWorkflowWriteAuthFilter.PREFIX)
@RequiredArgsConstructor
public class KnowledgeWorkflowWriteInternalController {

    static final int MAX_TEXT = 200_000;
    static final int CHUNK_SIZE = 800;

    private final KnowledgeBaseLookup knowledgeBases;
    private final KnowledgeService knowledgeService;

    @PostMapping("/text")
    public ApiResult<Map<String, Object>> writeText(@Valid @RequestBody WriteTextRequest request) {
        KnowledgeBase kb;
        try {
            kb = knowledgeBases.requireByCode(request.knowledgeBaseCode().trim());
        } catch (IllegalArgumentException missing) {
            return ApiResult.fail("知识库不存在");
        }
        if (kb.getStatus() == null || kb.getStatus() != 1) {
            return ApiResult.fail("知识库已停用");
        }
        if (!StringUtils.hasText(kb.getProjectCode())
                || !kb.getProjectCode().equals(request.projectCode() == null ? null : request.projectCode().trim())) {
            return ApiResult.fail("只能写入同一项目的知识库");
        }
        List<String> chunks = chunk(request.text());
        if (chunks.isEmpty()) return ApiResult.fail("要写入的内容为空");
        String fileId = "wf-" + sha256(request.knowledgeBaseCode() + "|" + request.idempotencyKey()).substring(0, 32);
        String title = StringUtils.hasText(request.title()) ? request.title().trim() : "工作流写入";
        try {
            knowledgeService.deleteByFileId(kb.getCode(), fileId);
        } catch (RuntimeException ignored) {
            // First delivery: nothing to replace.
        }
        KnowledgeImportRequest importRequest = new KnowledgeImportRequest();
        importRequest.setKnowledgeBaseCode(kb.getCode());
        importRequest.setFileId(fileId);
        importRequest.setFileName(title.length() > 120 ? title.substring(0, 120) : title);
        importRequest.setChunks(chunks);
        knowledgeService.importChunks(importRequest);
        return ApiResult.ok(Map.of("fileId", fileId, "chunkCount", chunks.size(), "knowledgeBaseCode", kb.getCode()));
    }

    /** Paragraph-aware split into chunks of at most {@link #CHUNK_SIZE} characters. */
    static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (!StringUtils.hasText(text)) return chunks;
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.trim().split("\\n\\s*\\n")) {
            String part = paragraph.trim();
            while (part.length() > CHUNK_SIZE) {
                if (!current.isEmpty()) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                chunks.add(part.substring(0, CHUNK_SIZE));
                part = part.substring(CHUNK_SIZE);
            }
            if (part.isEmpty()) continue;
            if (current.length() + part.length() + 2 > CHUNK_SIZE && !current.isEmpty()) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) current.append("\n\n");
            current.append(part);
        }
        if (!current.isEmpty()) chunks.add(current.toString());
        return chunks;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record WriteTextRequest(
            @NotBlank @Size(max = 128) String knowledgeBaseCode,
            @NotBlank @Size(max = MAX_TEXT) String text,
            @Size(max = 200) String title,
            @Size(max = 128) String projectCode,
            @NotBlank @Size(max = 256) String idempotencyKey) {
    }
}
