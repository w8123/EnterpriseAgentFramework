package com.enterprise.ai.bizindex.controller;

import com.enterprise.ai.bizindex.domain.dto.BizIndexRequest;
import com.enterprise.ai.bizindex.domain.dto.BizIndexStatsVO;
import com.enterprise.ai.bizindex.domain.dto.BizIndexVO;
import com.enterprise.ai.bizindex.service.BizIndexService;
import com.enterprise.ai.domain.dto.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 业务索引管理 —— 索引的注册、更新、删除、查询。
 */
@RestController
@RequestMapping("/biz-index")
@RequiredArgsConstructor
public class BizIndexController {

    private final BizIndexService bizIndexService;

    /** 注册新索引 */
    @PostMapping
    public ApiResult<Void> create(@Valid @RequestBody BizIndexRequest request) {
        bizIndexService.create(request);
        return ApiResult.ok();
    }

    /** 更新索引配置 */
    @PutMapping("/{indexCode}")
    public ApiResult<Void> update(@PathVariable String indexCode,
                                  @Valid @RequestBody BizIndexRequest request) {
        bizIndexService.update(indexCode, request);
        return ApiResult.ok();
    }

    /** 删除索引（含所有数据） */
    @DeleteMapping("/{indexCode}")
    public ApiResult<Void> delete(@PathVariable String indexCode) {
        bizIndexService.delete(indexCode);
        return ApiResult.ok();
    }

    /** 索引列表 */
    @GetMapping("/list")
    public ApiResult<List<BizIndexVO>> list() {
        return ApiResult.ok(bizIndexService.list());
    }

    /** 索引详情 */
    @GetMapping("/{indexCode}")
    public ApiResult<BizIndexVO> detail(@PathVariable String indexCode) {
        return ApiResult.ok(bizIndexService.detail(indexCode));
    }

    /** 索引统计 */
    @GetMapping("/{indexCode}/stats")
    public ApiResult<BizIndexStatsVO> stats(@PathVariable String indexCode) {
        return ApiResult.ok(bizIndexService.stats(indexCode));
    }
}
