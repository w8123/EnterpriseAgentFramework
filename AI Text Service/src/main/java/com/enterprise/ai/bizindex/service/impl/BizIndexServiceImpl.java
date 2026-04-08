package com.enterprise.ai.bizindex.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.bizindex.domain.dto.BizIndexRequest;
import com.enterprise.ai.bizindex.domain.dto.BizIndexStatsVO;
import com.enterprise.ai.bizindex.domain.dto.BizIndexVO;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndex;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndexAttachment;
import com.enterprise.ai.bizindex.domain.entity.BusinessIndexRecord;
import com.enterprise.ai.bizindex.repository.BusinessIndexAttachmentRepository;
import com.enterprise.ai.bizindex.repository.BusinessIndexRecordRepository;
import com.enterprise.ai.bizindex.repository.BusinessIndexRepository;
import com.enterprise.ai.bizindex.service.BizIndexService;
import com.enterprise.ai.bizindex.template.TemplateEngine;
import com.enterprise.ai.bizindex.vector.BizVectorService;
import com.enterprise.ai.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BizIndexServiceImpl implements BizIndexService {

    private final BusinessIndexRepository indexRepository;
    private final BusinessIndexRecordRepository recordRepository;
    private final BusinessIndexAttachmentRepository attachmentRepository;
    private final BizVectorService bizVectorService;
    private final EmbeddingService embeddingService;
    private final TemplateEngine templateEngine;

    @Override
    @Transactional
    public void create(BizIndexRequest request) {
        // 校验编码唯一性
        BusinessIndex existing = indexRepository.selectOne(
                new LambdaQueryWrapper<BusinessIndex>().eq(BusinessIndex::getIndexCode, request.getIndexCode()));
        if (existing != null) {
            throw new IllegalArgumentException("索引编码已存在: " + request.getIndexCode());
        }

        // 校验模板语法
        if (!templateEngine.validate(request.getTextTemplate())) {
            throw new IllegalArgumentException("模板格式无效，至少需要包含一个 {fieldName} 占位符");
        }

        BusinessIndex index = new BusinessIndex();
        BeanUtils.copyProperties(request, index);

        // 默认使用系统当前的 Embedding 配置
        if (index.getEmbeddingModel() == null || index.getEmbeddingModel().isBlank()) {
            index.setEmbeddingModel(embeddingService.getModelName());
        }
        if (index.getDimension() == null || index.getDimension() == 0) {
            index.setDimension(embeddingService.getDimension());
        }
        if (index.getChunkSize() == null) {
            index.setChunkSize(500);
        }
        if (index.getChunkOverlap() == null) {
            index.setChunkOverlap(50);
        }
        if (index.getSplitType() == null || index.getSplitType().isBlank()) {
            index.setSplitType("FIXED");
        }
        index.setStatus("ACTIVE");

        indexRepository.insert(index);

        // 创建 Milvus Collection
        bizVectorService.ensureCollection(index.getIndexCode(), index.getDimension());

        log.info("业务索引 [{}] 创建成功", index.getIndexCode());
    }

    @Override
    @Transactional
    public void update(String indexCode, BizIndexRequest request) {
        BusinessIndex index = getByCodeOrThrow(indexCode);

        if (request.getTextTemplate() != null && !request.getTextTemplate().isBlank()) {
            if (!templateEngine.validate(request.getTextTemplate())) {
                throw new IllegalArgumentException("模板格式无效");
            }
            index.setTextTemplate(request.getTextTemplate());
        }

        if (request.getIndexName() != null) index.setIndexName(request.getIndexName());
        if (request.getSourceSystem() != null) index.setSourceSystem(request.getSourceSystem());
        if (request.getFieldSchema() != null) index.setFieldSchema(request.getFieldSchema());
        if (request.getChunkSize() != null) index.setChunkSize(request.getChunkSize());
        if (request.getChunkOverlap() != null) index.setChunkOverlap(request.getChunkOverlap());
        if (request.getSplitType() != null) index.setSplitType(request.getSplitType());
        if (request.getRemark() != null) index.setRemark(request.getRemark());

        indexRepository.updateById(index);
        log.info("业务索引 [{}] 更新成功", indexCode);
    }

    @Override
    @Transactional
    public void delete(String indexCode) {
        BusinessIndex index = getByCodeOrThrow(indexCode);

        // 删除 MySQL 中的附件记录
        attachmentRepository.delete(
                new LambdaQueryWrapper<BusinessIndexAttachment>().eq(BusinessIndexAttachment::getIndexCode, indexCode));

        // 删除 MySQL 中的索引记录
        recordRepository.delete(
                new LambdaQueryWrapper<BusinessIndexRecord>().eq(BusinessIndexRecord::getIndexCode, indexCode));

        // 删除 Milvus Collection
        try {
            bizVectorService.dropCollection(indexCode);
        } catch (Exception e) {
            log.warn("删除 Milvus Collection [{}] 时出现异常，可能不存在: {}", indexCode, e.getMessage());
        }

        // 删除索引注册记录
        indexRepository.deleteById(index.getId());

        log.info("业务索引 [{}] 已完全删除", indexCode);
    }

    @Override
    public List<BizIndexVO> list() {
        List<BusinessIndex> indexes = indexRepository.selectList(
                new LambdaQueryWrapper<BusinessIndex>().orderByDesc(BusinessIndex::getCreateTime));

        return indexes.stream().map(idx -> {
            BizIndexVO vo = new BizIndexVO();
            BeanUtils.copyProperties(idx, vo);

            // 填充统计数据
            vo.setRecordCount(recordRepository.selectCount(
                    new LambdaQueryWrapper<BusinessIndexRecord>()
                            .eq(BusinessIndexRecord::getIndexCode, idx.getIndexCode())
                            .eq(BusinessIndexRecord::getStatus, "ACTIVE")));
            vo.setAttachmentChunkCount(attachmentRepository.selectCount(
                    new LambdaQueryWrapper<BusinessIndexAttachment>()
                            .eq(BusinessIndexAttachment::getIndexCode, idx.getIndexCode())
                            .eq(BusinessIndexAttachment::getStatus, "ACTIVE")));

            return vo;
        }).toList();
    }

    @Override
    public BizIndexVO detail(String indexCode) {
        BusinessIndex index = getByCodeOrThrow(indexCode);
        BizIndexVO vo = new BizIndexVO();
        BeanUtils.copyProperties(index, vo);

        vo.setRecordCount(recordRepository.selectCount(
                new LambdaQueryWrapper<BusinessIndexRecord>()
                        .eq(BusinessIndexRecord::getIndexCode, indexCode)
                        .eq(BusinessIndexRecord::getStatus, "ACTIVE")));
        vo.setAttachmentChunkCount(attachmentRepository.selectCount(
                new LambdaQueryWrapper<BusinessIndexAttachment>()
                        .eq(BusinessIndexAttachment::getIndexCode, indexCode)
                        .eq(BusinessIndexAttachment::getStatus, "ACTIVE")));
        return vo;
    }

    @Override
    public BizIndexStatsVO stats(String indexCode) {
        BusinessIndex index = getByCodeOrThrow(indexCode);

        long recordCount = recordRepository.selectCount(
                new LambdaQueryWrapper<BusinessIndexRecord>()
                        .eq(BusinessIndexRecord::getIndexCode, indexCode)
                        .eq(BusinessIndexRecord::getStatus, "ACTIVE"));

        long attachmentRecordCount = recordRepository.selectCount(
                new LambdaQueryWrapper<BusinessIndexRecord>()
                        .eq(BusinessIndexRecord::getIndexCode, indexCode)
                        .eq(BusinessIndexRecord::getStatus, "ACTIVE")
                        .eq(BusinessIndexRecord::getHasAttachment, true));

        long attachmentChunkCount = attachmentRepository.selectCount(
                new LambdaQueryWrapper<BusinessIndexAttachment>()
                        .eq(BusinessIndexAttachment::getIndexCode, indexCode)
                        .eq(BusinessIndexAttachment::getStatus, "ACTIVE"));

        return BizIndexStatsVO.builder()
                .indexCode(indexCode)
                .indexName(index.getIndexName())
                .recordCount(recordCount)
                .attachmentRecordCount(attachmentRecordCount)
                .attachmentChunkCount(attachmentChunkCount)
                .totalVectorCount(recordCount + attachmentChunkCount)
                .build();
    }

    /** 根据编码查询索引，不存在则抛异常 */
    private BusinessIndex getByCodeOrThrow(String indexCode) {
        BusinessIndex index = indexRepository.selectOne(
                new LambdaQueryWrapper<BusinessIndex>().eq(BusinessIndex::getIndexCode, indexCode));
        if (index == null) {
            throw new IllegalArgumentException("索引不存在: " + indexCode);
        }
        return index;
    }
}
