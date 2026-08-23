package com.enterprise.ai.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.domain.dto.DocumentImportAccessContext;
import com.enterprise.ai.domain.dto.DocumentImportJobResponse;
import com.enterprise.ai.domain.dto.DocumentImportResourceScope;
import com.enterprise.ai.internalauth.KnowledgeBizIndexConsoleAuthFilter;
import com.enterprise.ai.service.DocumentImportJobService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Internal durable-import API. Every route is guarded by the exact-body HMAC
 * filter and is reachable from browsers only through Control's authenticated
 * BFF.
 */
@RestController
@RequestMapping(KnowledgeBizIndexConsoleAuthFilter.DOCUMENT_IMPORT_PREFIX)
@RequiredArgsConstructor
public class DocumentImportJobController {

    private final DocumentImportJobService documentImportJobService;
    private final ObjectMapper objectMapper;

    @GetMapping("/knowledge-bases/{knowledgeBaseCode}/scope")
    public ApiResult<DocumentImportResourceScope> knowledgeBaseScope(
            HttpServletRequest request,
            @PathVariable String knowledgeBaseCode) {
        identity(request);
        return ApiResult.ok(documentImportJobService.describeKnowledgeBaseScope(knowledgeBaseCode));
    }

    @PostMapping("/jobs")
    public ApiResult<DocumentImportJobResponse> submit(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file,
            @RequestParam("knowledgeBaseCode") String knowledgeBaseCode,
            @RequestParam(value = "chunkStrategy", defaultValue = "fixed_length") String chunkStrategy,
            @RequestParam(value = "chunkSize", defaultValue = "500") Integer chunkSize,
            @RequestParam(value = "chunkOverlap", defaultValue = "50") Integer chunkOverlap,
            @RequestParam(value = "extraParams", required = false) String extraParamsJson,
            @RequestParam(value = "autoCommit", defaultValue = "false") boolean autoCommit,
            @RequestParam("workspaceId") String workspaceId,
            @RequestParam(value = "projectCode", required = false) String projectCode,
            @RequestParam("resourceScope") String resourceScope) {
        return ApiResult.ok(documentImportJobService.submit(
                file,
                knowledgeBaseCode,
                chunkStrategy,
                chunkSize,
                chunkOverlap,
                parseExtraParams(extraParamsJson),
                autoCommit,
                access(request, workspaceId, projectCode, resourceScope)));
    }

    @GetMapping("/jobs/{jobId}/scope")
    public ApiResult<DocumentImportResourceScope> jobScope(HttpServletRequest request,
                                                           @PathVariable String jobId) {
        return ApiResult.ok(documentImportJobService.describeJobScope(jobId, identity(request)));
    }

    @GetMapping("/jobs/{jobId}")
    public ApiResult<DocumentImportJobResponse> get(HttpServletRequest request,
                                                    @PathVariable String jobId) {
        return ApiResult.ok(documentImportJobService.get(jobId, false, identity(request)));
    }

    @GetMapping("/jobs/{jobId}/preview")
    public ApiResult<DocumentImportJobResponse> getWithPreview(HttpServletRequest request,
                                                               @PathVariable String jobId) {
        return ApiResult.ok(documentImportJobService.get(jobId, true, identity(request)));
    }

    @PostMapping("/jobs/{jobId}/commit")
    public ApiResult<DocumentImportJobResponse> commit(HttpServletRequest request,
                                                       @PathVariable String jobId) {
        return ApiResult.ok(documentImportJobService.commit(jobId, identity(request)));
    }

    @PostMapping("/jobs/{jobId}/retry")
    public ApiResult<DocumentImportJobResponse> retry(HttpServletRequest request,
                                                      @PathVariable String jobId) {
        return ApiResult.ok(documentImportJobService.retry(jobId, identity(request)));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public ApiResult<Void> cancel(HttpServletRequest request, @PathVariable String jobId) {
        documentImportJobService.cancel(jobId, identity(request));
        return ApiResult.ok();
    }

    @GetMapping("/files/{fileId}/scope")
    public ApiResult<DocumentImportResourceScope> fileScope(HttpServletRequest request,
                                                            @PathVariable String fileId) {
        identity(request);
        return ApiResult.ok(documentImportJobService.describeFileScope(fileId));
    }

    @PostMapping("/files/{fileId}/reparse")
    public ApiResult<DocumentImportJobResponse> reparse(
            HttpServletRequest request,
            @PathVariable String fileId,
            @RequestBody ScopeAssertion assertion) {
        if (assertion == null) {
            throw new IllegalArgumentException("知识库授权作用域不能为空");
        }
        return ApiResult.ok(documentImportJobService.reparse(fileId,
                access(request, assertion.workspaceId(), assertion.projectCode(), assertion.resourceScope())));
    }

    private DocumentImportAccessContext identity(HttpServletRequest request) {
        return access(request, null, null, null).identityOnly();
    }

    private DocumentImportAccessContext access(HttpServletRequest request,
                                               String workspaceId,
                                               String projectCode,
                                               String resourceScope) {
        Object tenant = request == null ? null
                : request.getAttribute(KnowledgeBizIndexConsoleAuthFilter.VERIFIED_TENANT_ATTRIBUTE);
        Object actor = request == null ? null
                : request.getAttribute(KnowledgeBizIndexConsoleAuthFilter.VERIFIED_ACTOR_ATTRIBUTE);
        if (!(tenant instanceof String tenantId) || tenantId.isBlank()
                || !(actor instanceof String actorId) || actorId.isBlank()) {
            throw new IllegalStateException("Control 到 Knowledge 的内部身份未通过验证");
        }
        return new DocumentImportAccessContext(tenantId, actorId, workspaceId, projectCode, resourceScope);
    }

    private Map<String, Object> parseExtraParams(String extraParamsJson) {
        if (extraParamsJson == null || extraParamsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(extraParamsJson, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException("extraParams 格式错误，应为 JSON 对象字符串", e);
        }
    }

    public record ScopeAssertion(String workspaceId, String projectCode, String resourceScope) {
    }
}
