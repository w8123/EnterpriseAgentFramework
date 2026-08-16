package com.enterprise.ai.bizindex.controller;

import com.enterprise.ai.bizindex.domain.dto.BizBatchUpsertRequest;
import com.enterprise.ai.bizindex.domain.dto.BizUpsertRequest;
import com.enterprise.ai.bizindex.service.BizIndexDataService;
import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.internalauth.KnowledgeProjectIngressAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal endpoint reached only after Capability has verified the project credential. */
@RestController
@RequestMapping("/internal/knowledge/project-ingress/projects/{projectCode}/biz-index/{indexCode}")
public class BizIndexProjectIngressController {

    private final BizIndexDataService dataService;

    public BizIndexProjectIngressController(BizIndexDataService dataService) {
        this.dataService = dataService;
    }

    @PostMapping("/upsert")
    public ApiResult<Void> upsert(HttpServletRequest servletRequest,
                                  @PathVariable String projectCode,
                                  @PathVariable String indexCode,
                                  @Valid @RequestBody BizUpsertRequest request) {
        requireVerifiedProject(servletRequest, projectCode);
        dataService.upsertForProject(projectCode, indexCode, request);
        return ApiResult.ok();
    }

    @PostMapping("/batch")
    public ApiResult<Void> batch(HttpServletRequest servletRequest,
                                 @PathVariable String projectCode,
                                 @PathVariable String indexCode,
                                 @Valid @RequestBody BizBatchUpsertRequest request) {
        requireVerifiedProject(servletRequest, projectCode);
        dataService.batchUpsertForProject(projectCode, indexCode, request.getItems());
        return ApiResult.ok();
    }

    @PostMapping("/delete")
    public ApiResult<Void> delete(HttpServletRequest servletRequest,
                                  @PathVariable String projectCode,
                                  @PathVariable String indexCode,
                                  @Valid @RequestBody DeleteRequest request) {
        requireVerifiedProject(servletRequest, projectCode);
        dataService.deleteRecordForProject(projectCode, indexCode, request.bizId());
        return ApiResult.ok();
    }

    private static void requireVerifiedProject(HttpServletRequest request, String projectCode) {
        Object verified = request == null ? null
                : request.getAttribute(KnowledgeProjectIngressAuthFilter.VERIFIED_PROJECT_ATTRIBUTE);
        if (!(verified instanceof String value) || !value.equals(projectCode)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "verified project identity does not match request path");
        }
    }

    public record DeleteRequest(
            @jakarta.validation.constraints.NotBlank(message = "业务主键不能为空") String bizId
    ) {
    }
}
