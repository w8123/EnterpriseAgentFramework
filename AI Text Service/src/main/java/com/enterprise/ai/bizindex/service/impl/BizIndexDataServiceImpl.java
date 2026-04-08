package com.enterprise.ai.bizindex.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.bizindex.domain.dto.BizUpsertRequest;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndex;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndexAttachment;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndexRecord;
import com.enterprise.ai.bizindex.repository.BusinessIndexAttachmentRepository;
import com.enterprise.ai.bizindex.repository.BusinessIndexRecordRepository;
import com.enterprise.ai.bizindex.repository.BusinessIndexRepository;
import com.enterprise.ai.bizindex.service.BizIndexDataService;
import com.enterprise.ai.bizindex.template.TemplateEngine;
import com.enterprise.ai.bizindex.vector.BizVectorService;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.chunk.ChunkStrategy;
import com.enterprise.ai.pipeline.chunk.ChunkStrategyFactory;
import com.enterprise.ai.pipeline.parser.DocumentParser;
import com.enterprise.ai.pipeline.parser.DocumentParserFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BizIndexDataServiceImpl implements BizIndexDataService {

    private final BusinessIndexRepository indexRepository;
    private final BusinessIndexRecordRepository recordRepository;
    private final BusinessIndexAttachmentRepository attachmentRepository;
    private final BizVectorService bizVectorService;
    private final EmbeddingService embeddingService;
    private final TemplateEngine templateEngine;
    private final DocumentParserFactory documentParserFactory;
    private final ChunkStrategyFactory chunkStrategyFactory;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void upsert(String indexCode, BizUpsertRequest request, List<MultipartFile> attachments) {
        BusinessIndex index = getIndexOrThrow(indexCode);

        // 1. 使用模板渲染 searchText
        String searchText = templateEngine.render(index.getTextTemplate(), request.getFields());
        log.debug("模板渲染结果 [{}]: {}", request.getBizId(), searchText);

        // 2. 检查是否已存在（upsert 语义）
        BusinessIndexRecord existingRecord = recordRepository.selectOne(
                new LambdaQueryWrapper<BusinessIndexRecord>()
                        .eq(BusinessIndexRecord::getIndexCode, indexCode)
                        .eq(BusinessIndexRecord::getBizId, request.getBizId()));

        if (existingRecord != null) {
            // 删除已有向量和附件数据，然后重建
            cleanExistingData(indexCode, request.getBizId(), existingRecord.getId());
        }

        // 3. 保存索引记录到 MySQL
        BusinessIndexRecord record = buildRecord(indexCode, request, searchText,
                attachments != null && !attachments.isEmpty());
        if (existingRecord != null) {
            record.setId(existingRecord.getId());
            recordRepository.updateById(record);
        } else {
            recordRepository.insert(record);
        }

        // 4. 主记录向量化并存入 Milvus
        String mainVectorId = UUID.randomUUID().toString();
        List<Float> mainVector = embeddingService.embed(searchText);

        bizVectorService.insert(indexCode,
                List.of(mainVectorId),
                List.of(request.getBizId()),
                List.of("FIELD"),
                List.of(truncateContent(searchText)),
                List.of(nullToEmpty(request.getOwnerUserId())),
                List.of(nullToEmpty(request.getOwnerOrgId())),
                List.of(nullToEmpty(request.getBizType())),
                List.of(""),
                List.of(mainVector));

        record.setVectorId(mainVectorId);
        recordRepository.updateById(record);

        // 5. 处理附件
        if (attachments != null && !attachments.isEmpty()) {
            processAttachments(index, record, request, attachments);
        }

        log.info("业务数据 [{}/{}] upsert 完成", indexCode, request.getBizId());
    }

    @Override
    @Transactional
    public void batchUpsert(String indexCode, List<BizUpsertRequest> items) {
        BusinessIndex index = getIndexOrThrow(indexCode);

        for (BizUpsertRequest item : items) {
            // 批量场景不含附件，复用 upsert 逻辑
            upsert(indexCode, item, null);
        }

        log.info("业务数据批量推送完成 [{}], 共 {} 条", indexCode, items.size());
    }

    @Override
    @Transactional
    public void deleteRecord(String indexCode, String bizId) {
        getIndexOrThrow(indexCode);

        BusinessIndexRecord record = recordRepository.selectOne(
                new LambdaQueryWrapper<BusinessIndexRecord>()
                        .eq(BusinessIndexRecord::getIndexCode, indexCode)
                        .eq(BusinessIndexRecord::getBizId, bizId));

        if (record == null) {
            throw new IllegalArgumentException("记录不存在: " + indexCode + "/" + bizId);
        }

        cleanExistingData(indexCode, bizId, record.getId());
        recordRepository.deleteById(record.getId());

        log.info("业务数据 [{}/{}] 已删除", indexCode, bizId);
    }

    @Override
    @Transactional
    public void rebuild(String indexCode) {
        BusinessIndex index = getIndexOrThrow(indexCode);

        List<BusinessIndexRecord> records = recordRepository.selectList(
                new LambdaQueryWrapper<BusinessIndexRecord>()
                        .eq(BusinessIndexRecord::getIndexCode, indexCode)
                        .eq(BusinessIndexRecord::getStatus, "ACTIVE"));

        if (records.isEmpty()) {
            log.info("索引 [{}] 无记录需要重建", indexCode);
            return;
        }

        log.info("开始重建索引 [{}], 共 {} 条记录", indexCode, records.size());

        for (BusinessIndexRecord record : records) {
            try {
                // 用最新模板重新渲染
                Map<String, String> fields = parseFieldsJson(record.getFieldsJson());
                String newSearchText = templateEngine.render(index.getTextTemplate(), fields);

                // 删除旧向量
                bizVectorService.deleteByBizId(indexCode, record.getBizId());

                // 重新向量化主记录
                String mainVectorId = UUID.randomUUID().toString();
                List<Float> mainVector = embeddingService.embed(newSearchText);

                bizVectorService.insert(indexCode,
                        List.of(mainVectorId),
                        List.of(record.getBizId()),
                        List.of("FIELD"),
                        List.of(truncateContent(newSearchText)),
                        List.of(nullToEmpty(record.getOwnerUserId())),
                        List.of(nullToEmpty(record.getOwnerOrgId())),
                        List.of(nullToEmpty(record.getBizType())),
                        List.of(""),
                        List.of(mainVector));

                record.setSearchText(newSearchText);
                record.setVectorId(mainVectorId);
                recordRepository.updateById(record);

                // 重建附件向量（使用已存储的 rawText）
                if (Boolean.TRUE.equals(record.getHasAttachment())) {
                    rebuildAttachmentVectors(index, record);
                }
            } catch (Exception e) {
                log.error("重建记录 [{}/{}] 失败: {}", indexCode, record.getBizId(), e.getMessage(), e);
            }
        }

        log.info("索引 [{}] 重建完成", indexCode);
    }

    // ======================== 私有方法 ========================

    /** 处理附件：解析 → 切分 → 向量化 → 存储 */
    private void processAttachments(BusinessIndex index, BusinessIndexRecord record,
                                    BizUpsertRequest request, List<MultipartFile> attachments) {
        ChunkStrategy chunkStrategy = chunkStrategyFactory.getStrategy(
                index.getSplitType() != null ? index.getSplitType().toLowerCase() : "fixed_length");

        for (MultipartFile attachment : attachments) {
            String fileName = attachment.getOriginalFilename();
            if (fileName == null || fileName.isBlank()) continue;

            // 解析文档
            DocumentParser parser = documentParserFactory.getParser(fileName);
            String rawText = parser.parse(attachment);

            if (rawText == null || rawText.isBlank()) {
                log.warn("附件 [{}] 解析结果为空，跳过", fileName);
                continue;
            }

            // 切分
            List<String> chunks = chunkStrategy.split(rawText, index.getChunkSize(), index.getChunkOverlap());

            // 批量向量化
            List<List<Float>> vectors = embeddingService.embedBatch(chunks);

            String fileType = extractFileType(fileName);

            // 逐 Chunk 存储
            List<String> vectorIds = new ArrayList<>();
            List<String> bizIds = new ArrayList<>();
            List<String> recordTypes = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            List<String> ownerUserIds = new ArrayList<>();
            List<String> ownerOrgIds = new ArrayList<>();
            List<String> bizTypes = new ArrayList<>();
            List<String> fileNames = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
                String vectorId = UUID.randomUUID().toString();
                vectorIds.add(vectorId);
                bizIds.add(request.getBizId());
                recordTypes.add("ATTACHMENT");
                contents.add(truncateContent(chunks.get(i)));
                ownerUserIds.add(nullToEmpty(request.getOwnerUserId()));
                ownerOrgIds.add(nullToEmpty(request.getOwnerOrgId()));
                bizTypes.add(nullToEmpty(request.getBizType()));
                fileNames.add(fileName);

                // 存储附件 Chunk 到 MySQL
                BusinessIndexAttachment att = new BusinessIndexAttachment();
                att.setIndexCode(index.getIndexCode());
                att.setBizId(request.getBizId());
                att.setRecordId(record.getId());
                att.setFileName(fileName);
                att.setFileType(fileType);
                att.setRawText(i == 0 ? rawText : null); // 仅第一个 Chunk 存储完整原文
                att.setChunkIndex(i);
                att.setChunkContent(chunks.get(i));
                att.setVectorId(vectorId);
                att.setStatus("ACTIVE");
                attachmentRepository.insert(att);
            }

            // 批量写入 Milvus
            bizVectorService.insert(index.getIndexCode(),
                    vectorIds, bizIds, recordTypes, contents,
                    ownerUserIds, ownerOrgIds, bizTypes, fileNames, vectors);

            log.info("附件 [{}] 处理完成，产生 {} 个 Chunk", fileName, chunks.size());
        }
    }

    /** 重建附件向量（索引重建时使用已存储的 rawText） */
    private void rebuildAttachmentVectors(BusinessIndex index, BusinessIndexRecord record) {
        // 查出该记录下每个文件的第一个 Chunk（持有 rawText）
        List<BusinessIndexAttachment> firstChunks = attachmentRepository.selectList(
                new LambdaQueryWrapper<BusinessIndexAttachment>()
                        .eq(BusinessIndexAttachment::getRecordId, record.getId())
                        .eq(BusinessIndexAttachment::getChunkIndex, 0)
                        .isNotNull(BusinessIndexAttachment::getRawText));

        // 先清除旧附件记录
        attachmentRepository.delete(
                new LambdaQueryWrapper<BusinessIndexAttachment>()
                        .eq(BusinessIndexAttachment::getRecordId, record.getId()));

        ChunkStrategy chunkStrategy = chunkStrategyFactory.getStrategy(
                index.getSplitType() != null ? index.getSplitType().toLowerCase() : "fixed_length");

        for (BusinessIndexAttachment firstChunk : firstChunks) {
            String rawText = firstChunk.getRawText();
            List<String> chunks = chunkStrategy.split(rawText, index.getChunkSize(), index.getChunkOverlap());
            List<List<Float>> vectors = embeddingService.embedBatch(chunks);

            List<String> vectorIds = new ArrayList<>();
            List<String> bizIds = new ArrayList<>();
            List<String> recordTypes = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            List<String> ownerUserIds = new ArrayList<>();
            List<String> ownerOrgIds = new ArrayList<>();
            List<String> bizTypes = new ArrayList<>();
            List<String> fileNames = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
                String vectorId = UUID.randomUUID().toString();
                vectorIds.add(vectorId);
                bizIds.add(record.getBizId());
                recordTypes.add("ATTACHMENT");
                contents.add(truncateContent(chunks.get(i)));
                ownerUserIds.add(nullToEmpty(record.getOwnerUserId()));
                ownerOrgIds.add(nullToEmpty(record.getOwnerOrgId()));
                bizTypes.add(nullToEmpty(record.getBizType()));
                fileNames.add(firstChunk.getFileName());

                BusinessIndexAttachment att = new BusinessIndexAttachment();
                att.setIndexCode(index.getIndexCode());
                att.setBizId(record.getBizId());
                att.setRecordId(record.getId());
                att.setFileName(firstChunk.getFileName());
                att.setFileType(firstChunk.getFileType());
                att.setRawText(i == 0 ? rawText : null);
                att.setChunkIndex(i);
                att.setChunkContent(chunks.get(i));
                att.setVectorId(vectorId);
                att.setStatus("ACTIVE");
                attachmentRepository.insert(att);
            }

            bizVectorService.insert(index.getIndexCode(),
                    vectorIds, bizIds, recordTypes, contents,
                    ownerUserIds, ownerOrgIds, bizTypes, fileNames, vectors);
        }
    }

    /** 清除已有数据（Milvus 向量 + MySQL 附件记录） */
    private void cleanExistingData(String indexCode, String bizId, Long recordId) {
        bizVectorService.deleteByBizId(indexCode, bizId);
        attachmentRepository.delete(
                new LambdaQueryWrapper<BusinessIndexAttachment>()
                        .eq(BusinessIndexAttachment::getRecordId, recordId));
    }

    private BusinessIndexRecord buildRecord(String indexCode, BizUpsertRequest request,
                                            String searchText, boolean hasAttachment) {
        BusinessIndexRecord record = new BusinessIndexRecord();
        record.setIndexCode(indexCode);
        record.setBizId(request.getBizId());
        record.setBizType(request.getBizType());
        record.setSearchText(searchText);
        record.setFieldsJson(toJson(request.getFields()));
        record.setMetadataJson(toJson(request.getMetadata()));
        record.setOwnerUserId(request.getOwnerUserId());
        record.setOwnerOrgId(request.getOwnerOrgId());
        record.setHasAttachment(hasAttachment);
        record.setStatus("ACTIVE");
        return record;
    }

    private BusinessIndex getIndexOrThrow(String indexCode) {
        BusinessIndex index = indexRepository.selectOne(
                new LambdaQueryWrapper<BusinessIndex>().eq(BusinessIndex::getIndexCode, indexCode));
        if (index == null) {
            throw new IllegalArgumentException("索引不存在: " + indexCode);
        }
        if (!"ACTIVE".equals(index.getStatus())) {
            throw new IllegalArgumentException("索引已停用: " + indexCode);
        }
        return index;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseFieldsJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("解析 fieldsJson 失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /** Milvus VarChar 最大 8192，超长需截断 */
    private String truncateContent(String text) {
        return (text != null && text.length() > 8000) ? text.substring(0, 8000) : text;
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private String extractFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
