package com.enterprise.ai.bizindex.controller;

import com.enterprise.ai.bizindex.domain.dto.BizSearchRequest;
import com.enterprise.ai.bizindex.domain.dto.BizSearchResponse;
import com.enterprise.ai.bizindex.service.BizIndexSearchService;
import com.enterprise.ai.domain.dto.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 业务索引语义搜索
 */
@RestController
@RequestMapping("/biz-index")
@RequiredArgsConstructor
public class BizIndexSearchController {

    private final BizIndexSearchService bizIndexSearchService;

    /** 单索引语义搜索 */
    @PostMapping("/{indexCode}/search")
    public ApiResult<BizSearchResponse> search(@PathVariable String indexCode,
                                               @Valid @RequestBody BizSearchRequest request) {
        return ApiResult.ok(bizIndexSearchService.search(indexCode, request));
    }
}
