package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.domain.dto.*;
import com.enterprise.ai.domain.entity.*;
import com.enterprise.ai.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/** Knowledge-owned operational read projections; import and mutation remain in their services. */
@Service
@RequiredArgsConstructor
public class KnowledgeOperationsQuery {
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileInfoRepository fileInfoRepository;
    private final ChunkRepository chunkRepository;
    private final KnowledgeTagRepository knowledgeTagRepository;
    private final KnowledgeQuestionRepository knowledgeQuestionRepository;
    private final KnowledgeHitLogRepository knowledgeHitLogRepository;
    private final KnowledgeBaseLookup knowledgeBaseLookup;

    @Value("${rag.score-threshold:0.5}")
    private float defaultScoreThreshold;

    public List<KnowledgeBaseVO> listAll() {
        List<KnowledgeBase> list = knowledgeBaseRepository.selectList(null);
        return list.stream().map(kb -> {
            KnowledgeBaseVO vo = new KnowledgeBaseVO();
            BeanUtils.copyProperties(kb, vo);
            Long count = fileInfoRepository.selectCount(
                    new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getKnowledgeBaseId, kb.getId()));
            vo.setFileCount(count != null ? count.intValue() : 0);
            Long chunkCount = chunkRepository.selectCount(
                    new LambdaQueryWrapper<Chunk>().eq(Chunk::getKnowledgeBaseId, kb.getId()));
            vo.setChunkCount(chunkCount != null ? chunkCount.intValue() : 0);
            Long questionCount = knowledgeQuestionRepository.selectCount(
                    new LambdaQueryWrapper<KnowledgeQuestion>().eq(KnowledgeQuestion::getKnowledgeBaseId, kb.getId()));
            vo.setQuestionCount(questionCount != null ? questionCount.intValue() : 0);
            Long tagCount = knowledgeTagRepository.selectCount(
                    new LambdaQueryWrapper<KnowledgeTag>().eq(KnowledgeTag::getKnowledgeBaseId, kb.getId()));
            vo.setTagCount(tagCount != null ? tagCount.intValue() : 0);
            vo.setHitCount(sumHitCount(kb.getId()));
            return vo;
        }).collect(Collectors.toList());
    }

    public List<KnowledgeHitLogDTO> listHitLogs(String kbCode, Integer limit, Boolean lowConfidenceOnly) {
        KnowledgeBase kb = knowledgeBaseLookup.requireByCode(kbCode);
        LambdaQueryWrapper<KnowledgeHitLog> query = new LambdaQueryWrapper<KnowledgeHitLog>()
                .eq(KnowledgeHitLog::getKnowledgeBaseId, kb.getId())
                .orderByDesc(KnowledgeHitLog::getCreateTime)
                .last("LIMIT " + Math.max(1, Math.min(limit != null ? limit : 50, 200)));
        if (Boolean.TRUE.equals(lowConfidenceOnly)) {
            query.lt(KnowledgeHitLog::getScore, valueOrDefault(kb.getSimilarityThreshold(), defaultScoreThreshold));
        }
        List<KnowledgeHitLog> logs = knowledgeHitLogRepository.selectList(query);
        return toHitLogDTOs(logs);
    }

    public KnowledgeOpsDashboardVO getOpsDashboard(String kbCode) {
        KnowledgeBase kb = knowledgeBaseLookup.requireByCode(kbCode);
        List<FileInfo> recentFiles = fileInfoRepository.selectList(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getKnowledgeBaseId, kb.getId())
                .orderByDesc(FileInfo::getCreateTime)
                .last("LIMIT 8"));
        List<ChunkVO> hotChunks = chunkRepository.selectList(new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getKnowledgeBaseId, kb.getId())
                        .orderByDesc(Chunk::getHitCount)
                        .last("LIMIT 8"))
                .stream().map(KnowledgeChunkProjection::toChunkVO).collect(Collectors.toList());
        List<ChunkVO> zeroHitChunks = chunkRepository.selectList(new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getKnowledgeBaseId, kb.getId())
                        .and(w -> w.eq(Chunk::getHitCount, 0).or().isNull(Chunk::getHitCount))
                        .orderByDesc(Chunk::getCreateTime)
                        .last("LIMIT 8"))
                .stream().map(KnowledgeChunkProjection::toChunkVO).collect(Collectors.toList());
        return KnowledgeOpsDashboardVO.builder()
                .stats(getStats(kbCode))
                .recentFiles(recentFiles.stream().map(this::toPipelineFileStatus).collect(Collectors.toList()))
                .hotChunks(hotChunks)
                .zeroHitChunks(zeroHitChunks)
                .recentHits(listHitLogs(kbCode, 8, false))
                .lowConfidenceHits(listHitLogs(kbCode, 8, true))
                .build();
    }

    public KnowledgeStatsVO getStats(String kbCode) {
        KnowledgeBase kb = knowledgeBaseLookup.requireByCode(kbCode);
        return KnowledgeStatsVO.builder()
                .knowledgeBaseCode(kbCode)
                .fileCount(countFiles(kb.getId()))
                .chunkCount(countChunks(kb.getId()))
                .activeChunkCount(countActiveChunks(kb.getId()))
                .questionCount(countQuestions(kb.getId()))
                .tagCount(countTags(kb.getId()))
                .hitCount(sumHitCount(kb.getId()))
                .build();
    }

    private List<KnowledgeHitLogDTO> toHitLogDTOs(List<KnowledgeHitLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return List.of();
        }
        Set<Long> chunkIds = logs.stream()
                .map(KnowledgeHitLog::getChunkId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Chunk> chunkMap = chunkIds.isEmpty()
                ? Map.of()
                : chunkRepository.selectList(new LambdaQueryWrapper<Chunk>().in(Chunk::getId, chunkIds))
                        .stream().collect(Collectors.toMap(Chunk::getId, c -> c));
        Set<String> fileIds = chunkMap.values().stream()
                .map(Chunk::getFileId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> fileNameMap = fileIds.isEmpty()
                ? Map.of()
                : fileInfoRepository.selectList(new LambdaQueryWrapper<FileInfo>().in(FileInfo::getFileId, fileIds))
                        .stream().collect(Collectors.toMap(FileInfo::getFileId, FileInfo::getFileName, (a, b) -> a));
        return logs.stream().map(log -> {
            Chunk chunk = log.getChunkId() != null ? chunkMap.get(log.getChunkId()) : null;
            return KnowledgeHitLogDTO.builder()
                    .id(log.getId())
                    .chunkId(log.getChunkId())
                    .queryText(log.getQueryText())
                    .searchMode(log.getSearchMode())
                    .score(log.getScore())
                    .directReturn(log.getDirectReturn() != null && log.getDirectReturn() == 1)
                    .fileId(chunk != null ? chunk.getFileId() : null)
                    .fileName(chunk != null ? fileNameMap.get(chunk.getFileId()) : null)
                    .chunkIndex(chunk != null ? chunk.getChunkIndex() : null)
                    .userId(log.getUserId())
                    .traceId(log.getTraceId())
                    .createTime(log.getCreateTime())
                    .build();
        }).collect(Collectors.toList());
    }

    private KnowledgeOpsDashboardVO.PipelineFileStatus toPipelineFileStatus(FileInfo file) {
        // File metadata records an aggregate outcome, not individual pipeline events.
        // Keep missing stage evidence empty instead of inventing five identical outcomes.
        return KnowledgeOpsDashboardVO.PipelineFileStatus.builder()
                .fileId(file.getFileId())
                .fileName(file.getFileName())
                .status(file.getStatus())
                .chunkCount(file.getChunkCount())
                .steps(List.of())
                .build();
    }

    private int countFiles(Long kbId) {
        Long count = fileInfoRepository.selectCount(new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getKnowledgeBaseId, kbId));
        return count != null ? count.intValue() : 0;
    }

    private int countChunks(Long kbId) {
        Long count = chunkRepository.selectCount(new LambdaQueryWrapper<Chunk>().eq(Chunk::getKnowledgeBaseId, kbId));
        return count != null ? count.intValue() : 0;
    }

    private int countActiveChunks(Long kbId) {
        Long count = chunkRepository.selectCount(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getKnowledgeBaseId, kbId)
                .ne(Chunk::getEnabled, 0));
        return count != null ? count.intValue() : 0;
    }

    private int countQuestions(Long kbId) {
        Long count = knowledgeQuestionRepository.selectCount(new LambdaQueryWrapper<KnowledgeQuestion>().eq(KnowledgeQuestion::getKnowledgeBaseId, kbId));
        return count != null ? count.intValue() : 0;
    }

    private int countTags(Long kbId) {
        Long count = knowledgeTagRepository.selectCount(new LambdaQueryWrapper<KnowledgeTag>().eq(KnowledgeTag::getKnowledgeBaseId, kbId));
        return count != null ? count.intValue() : 0;
    }

    private int sumHitCount(Long kbId) {
        Long total = chunkRepository.sumHitCount(kbId);
        return total != null ? total.intValue() : 0;
    }

    private float valueOrDefault(Float value, float fallback) {
        return value != null ? value : fallback;
    }
}
