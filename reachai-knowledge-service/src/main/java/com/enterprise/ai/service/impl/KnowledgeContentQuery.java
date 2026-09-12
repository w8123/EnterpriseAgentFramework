package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.domain.dto.ChunkVO;
import com.enterprise.ai.domain.dto.FileInfoVO;
import com.enterprise.ai.domain.entity.Chunk;
import com.enterprise.ai.domain.entity.FileInfo;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.domain.entity.KnowledgeTag;
import com.enterprise.ai.domain.vo.SimilarItem;
import com.enterprise.ai.repository.ChunkRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import com.enterprise.ai.repository.KnowledgeBaseLookup;
import com.enterprise.ai.repository.KnowledgeTagRepository;
import org.springframework.beans.BeanUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 知识文件与片段的管理查询，不执行索引或生命周期写入。 */
@Service
@RequiredArgsConstructor
public class KnowledgeContentQuery {
    private final FileInfoRepository fileInfoRepository;
    private final ChunkRepository chunkRepository;
    private final KnowledgeTagRepository knowledgeTagRepository;
    private final KnowledgeBaseLookup knowledgeBaseLookup;

    public void enrichFileName(List<SimilarItem> items) {
        if (items == null || items.isEmpty()) return;
        Set<String> fileIds = items.stream().map(SimilarItem::getFileId).collect(Collectors.toSet());
        List<FileInfo> files = fileInfoRepository.selectList(
                new LambdaQueryWrapper<FileInfo>().in(FileInfo::getFileId, fileIds));
        Map<String, String> fileNameMap = files.stream()
                .collect(Collectors.toMap(FileInfo::getFileId, FileInfo::getFileName, (a, b) -> a));
        items.forEach(item -> item.setFileName(fileNameMap.getOrDefault(item.getFileId(), "未知文件")));
    }

    public List<FileInfoVO> getFilesByKbCode(String kbCode) {
        KnowledgeBase kb = knowledgeBaseLookup.requireByCode(kbCode);
        List<FileInfo> files = fileInfoRepository.selectList(
                new LambdaQueryWrapper<FileInfo>()
                        .eq(FileInfo::getKnowledgeBaseId, kb.getId())
                        .orderByDesc(FileInfo::getCreateTime));
        return files.stream().map(this::toFileInfoVO).collect(Collectors.toList());
    }

    public List<ChunkVO> getChunksByFileId(String fileId) {
        List<Chunk> chunks = chunkRepository.selectList(
                new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getFileId, fileId)
                        .orderByAsc(Chunk::getChunkIndex));
        return chunks.stream().map(KnowledgeChunkProjection::toChunkVO).collect(Collectors.toList());
    }

    public List<ChunkVO> listChunks(String kbCode, String keyword, Integer enabled, String tagKey, String tagValue, Integer limit) {
        KnowledgeBase kb = knowledgeBaseLookup.requireByCode(kbCode);
        LambdaQueryWrapper<Chunk> query = new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getKnowledgeBaseId, kb.getId())
                .orderByDesc(Chunk::getHitCount)
                .orderByAsc(Chunk::getChunkIndex);
        if (keyword != null && !keyword.isBlank()) {
            query.and(w -> w.like(Chunk::getContent, keyword).or().like(Chunk::getTitle, keyword));
        }
        if (enabled != null) {
            query.eq(Chunk::getEnabled, enabled);
        }
        if ((tagKey != null && !tagKey.isBlank()) || (tagValue != null && !tagValue.isBlank())) {
            LambdaQueryWrapper<KnowledgeTag> tagQuery = new LambdaQueryWrapper<KnowledgeTag>()
                    .eq(KnowledgeTag::getKnowledgeBaseId, kb.getId())
                    .eq(KnowledgeTag::getTargetType, "CHUNK");
            if (tagKey != null && !tagKey.isBlank()) {
                tagQuery.eq(KnowledgeTag::getTagKey, tagKey);
            }
            if (tagValue != null && !tagValue.isBlank()) {
                tagQuery.eq(KnowledgeTag::getTagValue, tagValue);
            }
            List<Long> chunkIds = knowledgeTagRepository.selectList(tagQuery).stream()
                    .map(KnowledgeTag::getTargetId)
                    .filter(Objects::nonNull)
                    .map(this::parseLongOrNull)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            if (chunkIds.isEmpty()) {
                return List.of();
            }
            query.in(Chunk::getId, chunkIds);
        }
        query.last("LIMIT " + Math.max(1, Math.min(limit != null ? limit : 200, 500)));
        return chunkRepository.selectList(query).stream().map(KnowledgeChunkProjection::toChunkVO).collect(Collectors.toList());
    }

    private Long parseLongOrNull(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private FileInfoVO toFileInfoVO(FileInfo file) {
        FileInfoVO vo = new FileInfoVO();
        BeanUtils.copyProperties(file, vo);
        return vo;
    }

}
