package com.enterprise.ai.controller;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.domain.dto.DedupRequest;
import com.enterprise.ai.domain.dto.DedupResponse;
import com.enterprise.ai.service.DedupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dedup")
@RequiredArgsConstructor
public class DedupController {

    private final DedupService dedupService;

    /**
     * POST /ai/dedup/check
     * 语义查重接口 — 返回与输入文本相似的内容列表
     */
    @PostMapping("/check")
    public ApiResult<DedupResponse> check(@Valid @RequestBody DedupRequest request) {
        DedupResponse response = dedupService.check(request);
        return ApiResult.ok(response);
    }
}
