package com.enterprise.ai.bizindex.controller;

import com.enterprise.ai.bizindex.domain.dto.BizBatchUpsertRequest;
import com.enterprise.ai.bizindex.domain.dto.BizUpsertRequest;
import com.enterprise.ai.bizindex.service.BizIndexDataService;
import com.enterprise.ai.domain.dto.ApiResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 业务索引数据同步 —— 推送（upsert）、删除、批量同步、索引重建。
 *
 * <h3>Upsert 接口使用说明</h3>
 * <p>使用 multipart/form-data 格式：</p>
 * <ul>
 *   <li>Part "data" (application/json) — 业务数据 JSON，参见 {@link BizUpsertRequest}</li>
 *   <li>Part "attachments" (file，可选，支持多个) — 附件文件</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/biz-index/{indexCode}")
@RequiredArgsConstructor
public class BizIndexDataController {

    private final BizIndexDataService bizIndexDataService;
    private final ObjectMapper objectMapper;

    /**
     * 推送单条业务数据（支持附件上传）
     *
     * @param indexCode   索引编码
     * @param dataJson    业务数据 JSON 字符串
     * @param attachments 附件文件列表（可选）
     */
    @PostMapping("/upsert")
    public ApiResult<Void> upsert(@PathVariable String indexCode,
                                  @RequestPart("data") String dataJson,
                                  @RequestPart(value = "attachments", required = false) List<MultipartFile> attachments) {
        BizUpsertRequest request = parseUpsertRequest(dataJson);
        bizIndexDataService.upsert(indexCode, request, attachments);
        return ApiResult.ok();
    }

    /** 批量推送（仅结构化字段，不含附件） */
    @PostMapping("/batch")
    public ApiResult<Void> batchUpsert(@PathVariable String indexCode,
                                       @Valid @RequestBody BizBatchUpsertRequest request) {
        bizIndexDataService.batchUpsert(indexCode, request.getItems());
        return ApiResult.ok();
    }

    /** 删除单条业务记录 */
    @DeleteMapping("/record/{bizId}")
    public ApiResult<Void> deleteRecord(@PathVariable String indexCode,
                                        @PathVariable String bizId) {
        bizIndexDataService.deleteRecord(indexCode, bizId);
        return ApiResult.ok();
    }

    /** 重建索引（模板变更后使用） */
    @PostMapping("/rebuild")
    public ApiResult<Void> rebuild(@PathVariable String indexCode) {
        bizIndexDataService.rebuild(indexCode);
        return ApiResult.ok();
    }

    private BizUpsertRequest parseUpsertRequest(String json) {
        try {
            return objectMapper.readValue(json, BizUpsertRequest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("data 参数 JSON 格式错误: " + e.getMessage());
        }
    }
}
