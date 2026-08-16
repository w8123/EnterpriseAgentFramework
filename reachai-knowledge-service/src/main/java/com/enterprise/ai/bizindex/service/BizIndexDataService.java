package com.enterprise.ai.bizindex.service;

import com.enterprise.ai.bizindex.domain.dto.BizUpsertRequest;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 业务索引数据同步服务 —— 数据推送（upsert）、删除、批量同步、索引重建。
 */
public interface BizIndexDataService {

    /**
     * 推送单条业务数据（支持附件）
     *
     * @param indexCode   索引编码
     * @param request     业务数据
     * @param attachments 附件列表（可为空）
     */
    void upsert(String indexCode, BizUpsertRequest request, List<MultipartFile> attachments);

    /** Project-credential variant; the target index must belong to the verified project. */
    void upsertForProject(String projectCode, String indexCode, BizUpsertRequest request);

    /**
     * 批量推送业务数据（仅结构化字段，不含附件）
     */
    void batchUpsert(String indexCode, List<BizUpsertRequest> items);

    /** Project-credential batch variant with mandatory index ownership enforcement. */
    void batchUpsertForProject(String projectCode, String indexCode, List<BizUpsertRequest> items);

    /**
     * 删除单条业务记录（含向量和附件）
     */
    void deleteRecord(String indexCode, String bizId);

    /** Project-credential delete variant with mandatory index ownership enforcement. */
    void deleteRecordForProject(String projectCode, String indexCode, String bizId);

    /**
     * 重建索引 —— 使用最新模板重新渲染所有记录的 searchText 并刷新向量
     */
    void rebuild(String indexCode);
}
