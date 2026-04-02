package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.domain.dto.*;
import com.enterprise.ai.domain.entity.Chunk;
import com.enterprise.ai.domain.entity.FileInfo;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.domain.vo.SimilarItem;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.chunk.ChunkStrategy;
import com.enterprise.ai.pipeline.chunk.ChunkStrategyFactory;
import com.enterprise.ai.pipeline.parser.DocumentParser;
import com.enterprise.ai.pipeline.parser.DocumentParserFactory;
import com.enterprise.ai.repository.ChunkRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import com.enterprise.ai.service.KnowledgeService;
import com.enterprise.ai.vector.VectorSearchRequest;
import com.enterprise.ai.vector.VectorSearchResult;
import com.enterprise.ai.vector.VectorService;
import org.springframework.beans.BeanUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements KnowledgeService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileInfoRepository fileInfoRepository;
    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final VectorService vectorService;
    private final DocumentParserFactory documentParserFactory;
    private final ChunkStrategyFactory chunkStrategyFactory;

    @Value("${milvus.dimension:1536}")
    private int dimension;

    @Value("${rag.default-top-k:5}")
    private int defaultTopK;

    @Value("${rag.score-threshold:0.5}")
    private float defaultScoreThreshold;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void importChunks(KnowledgeImportRequest request) {
        // 1. 查询知识库
        KnowledgeBase kb = knowledgeBaseRepository.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getCode, request.getKnowledgeBaseCode()));
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在: " + request.getKnowledgeBaseCode());
        }

        // 2. 确保 Milvus collection 存在
        vectorService.ensureCollection(kb.getCode(), kb.getDimension() != null ? kb.getDimension() : dimension);

        // 3. 批量 Embedding
        List<String> texts = request.getChunks();
        List<List<Float>> vectors = embeddingService.embedBatch(texts);

        // 4. 生成 ID 列表
        List<String> ids = IntStream.range(0, texts.size())
                .mapToObj(i -> request.getFileId() + "_" + i)
                .collect(Collectors.toList());

        List<String> fileIds = Collections.nCopies(texts.size(), request.getFileId());

        // 5. 插入 Milvus
        vectorService.insert(kb.getCode(), ids, vectors, fileIds, texts);

        // 6. 保存文件记录
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileId(request.getFileId());
        fileInfo.setKnowledgeBaseId(kb.getId());
        fileInfo.setFileName(request.getFileName());
        fileInfo.setChunkCount(texts.size());
        fileInfo.setStatus(1);
        fileInfoRepository.insert(fileInfo);

        // 7. 保存 chunk 记录
        for (int i = 0; i < texts.size(); i++) {
            Chunk chunk = new Chunk();
            chunk.setFileId(request.getFileId());
            chunk.setKnowledgeBaseId(kb.getId());
            chunk.setContent(texts.get(i));
            chunk.setChunkIndex(i);
            chunk.setVectorId(ids.get(i));
            chunk.setCollectionName(kb.getCode());
            chunkRepository.insert(chunk);
        }

        log.info("导入完成: knowledgeBase={}, fileId={}, chunks={}", kb.getCode(), request.getFileId(), texts.size());
    }

    @Override
    public List<KnowledgeBase> resolveKnowledgeBases(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return knowledgeBaseRepository.selectList(
                    new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getStatus, 1));
        }
        return knowledgeBaseRepository.selectList(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .in(KnowledgeBase::getCode, codes)
                        .eq(KnowledgeBase::getStatus, 1));
    }

    @Override
    public void enrichFileName(List<SimilarItem> items) {
        if (items == null || items.isEmpty()) return;
        Set<String> fileIds = items.stream().map(SimilarItem::getFileId).collect(Collectors.toSet());
        List<FileInfo> files = fileInfoRepository.selectList(
                new LambdaQueryWrapper<FileInfo>().in(FileInfo::getFileId, fileIds));
        Map<String, String> fileNameMap = files.stream()
                .collect(Collectors.toMap(FileInfo::getFileId, FileInfo::getFileName, (a, b) -> a));
        items.forEach(item -> item.setFileName(fileNameMap.getOrDefault(item.getFileId(), "未知文件")));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByFileId(String knowledgeBaseCode, String fileId) {
        vectorService.deleteByFileId(knowledgeBaseCode, fileId);
        chunkRepository.delete(new LambdaQueryWrapper<Chunk>().eq(Chunk::getFileId, fileId));
        fileInfoRepository.delete(new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileId, fileId));
        log.info("删除文件数据: knowledgeBase={}, fileId={}", knowledgeBaseCode, fileId);
    }

    // ==================== 知识库 CRUD ====================

    @Override
    public List<KnowledgeBaseVO> listAll() {
        List<KnowledgeBase> list = knowledgeBaseRepository.selectList(null);
        return list.stream().map(kb -> {
            KnowledgeBaseVO vo = new KnowledgeBaseVO();
            BeanUtils.copyProperties(kb, vo);
            Long count = fileInfoRepository.selectCount(
                    new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getKnowledgeBaseId, kb.getId()));
            vo.setFileCount(count != null ? count.intValue() : 0);
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(KnowledgeBaseRequest request) {
        KnowledgeBase existing = knowledgeBaseRepository.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getCode, request.getCode()));
        if (existing != null) {
            throw new IllegalArgumentException("知识库编码已存在: " + request.getCode());
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.getName());
        kb.setCode(request.getCode());
        kb.setDescription(request.getDescription());
        kb.setEmbeddingModel(request.getEmbeddingModel());
        kb.setDimension(request.getDimension() != null ? request.getDimension() : dimension);
        kb.setStatus(1);
        knowledgeBaseRepository.insert(kb);
        vectorService.ensureCollection(kb.getCode(), kb.getDimension());
        log.info("创建知识库: code={}, name={}", kb.getCode(), kb.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(KnowledgeBaseRequest request) {
        KnowledgeBase kb = knowledgeBaseRepository.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getCode, request.getCode()));
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在: " + request.getCode());
        }
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        if (request.getEmbeddingModel() != null) {
            kb.setEmbeddingModel(request.getEmbeddingModel());
        }
        knowledgeBaseRepository.updateById(kb);
        log.info("更新知识库: code={}", kb.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByCode(String code) {
        KnowledgeBase kb = knowledgeBaseRepository.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getCode, code));
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在: " + code);
        }
        chunkRepository.delete(new LambdaQueryWrapper<Chunk>().eq(Chunk::getKnowledgeBaseId, kb.getId()));
        fileInfoRepository.delete(new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getKnowledgeBaseId, kb.getId()));
        knowledgeBaseRepository.deleteById(kb.getId());
        try {
            vectorService.dropCollection(code);
        } catch (Exception e) {
            log.warn("删除向量集合失败（可能不存在）: {}", code, e);
        }
        log.info("删除知识库: code={}", code);
    }

    // ==================== Chunk 预览 ====================

    @Override
    public ChunkPreviewResponse previewChunks(MultipartFile file, String chunkStrategy,
                                               Integer chunkSize, Integer chunkOverlap) {
        String fileName = file.getOriginalFilename();
        int size = chunkSize != null ? chunkSize : 500;
        int overlap = chunkOverlap != null ? chunkOverlap : 50;
        String strategy = chunkStrategy != null ? chunkStrategy : "fixed_length";

        DocumentParser parser = documentParserFactory.getParser(fileName);
        String rawText = parser.parse(file);

        ChunkStrategy chunker = chunkStrategyFactory.getStrategy(strategy);
        List<String> textChunks = chunker.split(rawText, size, overlap);

        List<ChunkPreviewResponse.ChunkItem> items = IntStream.range(0, textChunks.size())
                .mapToObj(i -> new ChunkPreviewResponse.ChunkItem(i, textChunks.get(i), textChunks.get(i).length()))
                .collect(Collectors.toList());

        return new ChunkPreviewResponse(fileName, strategy, size, overlap, items.size(), items);
    }

    // ==================== V2 新增：知识库详情/文件管理/检索测试 ====================

    @Override
    public List<FileInfoVO> getFilesByKbCode(String kbCode) {
        KnowledgeBase kb = findKbByCode(kbCode);
        List<FileInfo> files = fileInfoRepository.selectList(
                new LambdaQueryWrapper<FileInfo>()
                        .eq(FileInfo::getKnowledgeBaseId, kb.getId())
                        .orderByDesc(FileInfo::getCreateTime));
        return files.stream().map(f -> {
            FileInfoVO vo = new FileInfoVO();
            BeanUtils.copyProperties(f, vo);
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public List<ChunkVO> getChunksByFileId(String fileId) {
        List<Chunk> chunks = chunkRepository.selectList(
                new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getFileId, fileId)
                        .orderByAsc(Chunk::getChunkIndex));
        return chunks.stream().map(c -> {
            ChunkVO vo = new ChunkVO();
            BeanUtils.copyProperties(c, vo);
            vo.setLength(c.getContent() != null ? c.getContent().length() : 0);
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteFileById(String fileId) {
        FileInfo fileInfo = fileInfoRepository.selectOne(
                new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileId, fileId));
        if (fileInfo == null) {
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        KnowledgeBase kb = knowledgeBaseRepository.selectById(fileInfo.getKnowledgeBaseId());
        if (kb == null) {
            throw new IllegalArgumentException("文件所属知识库不存在");
        }
        // 同时删除向量库数据、chunk记录和文件记录
        try {
            vectorService.deleteByFileId(kb.getCode(), fileId);
        } catch (Exception e) {
            log.warn("删除向量数据失败（可能集合不存在）: fileId={}, error={}", fileId, e.getMessage());
        }
        chunkRepository.delete(new LambdaQueryWrapper<Chunk>().eq(Chunk::getFileId, fileId));
        fileInfoRepository.deleteById(fileInfo.getId());
        log.info("删除文件: fileId={}, kb={}", fileId, kb.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reparseFile(String fileId) {
        FileInfo fileInfo = fileInfoRepository.selectOne(
                new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getFileId, fileId));
        if (fileInfo == null) {
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        if (fileInfo.getRawText() == null || fileInfo.getRawText().isBlank()) {
            throw new IllegalStateException("该文件没有保存原始文本，无法重新解析");
        }
        KnowledgeBase kb = knowledgeBaseRepository.selectById(fileInfo.getKnowledgeBaseId());
        if (kb == null) {
            throw new IllegalArgumentException("文件所属知识库不存在");
        }

        // 标记为解析中
        fileInfo.setStatus(0);
        fileInfoRepository.updateById(fileInfo);

        try {
            // 1. 删除旧向量和旧chunk
            try {
                vectorService.deleteByFileId(kb.getCode(), fileId);
            } catch (Exception e) {
                log.warn("删除旧向量数据失败: {}", e.getMessage());
            }
            chunkRepository.delete(new LambdaQueryWrapper<Chunk>().eq(Chunk::getFileId, fileId));

            // 2. 使用知识库最新配置重新切分
            String strategyName = mapSplitType(kb.getSplitType());
            int chunkSize = kb.getChunkSize() != null ? kb.getChunkSize() : 500;
            int chunkOverlap = kb.getChunkOverlap() != null ? kb.getChunkOverlap() : 50;

            ChunkStrategy chunker = chunkStrategyFactory.getStrategy(strategyName);
            List<String> textChunks = chunker.split(fileInfo.getRawText(), chunkSize, chunkOverlap);

            // 3. 重新生成向量
            vectorService.ensureCollection(kb.getCode(), kb.getDimension() != null ? kb.getDimension() : dimension);
            List<List<Float>> vectors = embeddingService.embedBatch(textChunks);
            List<String> ids = IntStream.range(0, textChunks.size())
                    .mapToObj(i -> fileId + "_chunk_" + i)
                    .collect(Collectors.toList());
            List<String> fileIds = Collections.nCopies(textChunks.size(), fileId);
            vectorService.insert(kb.getCode(), ids, vectors, fileIds, textChunks);

            // 4. 重新保存chunk记录
            for (int i = 0; i < textChunks.size(); i++) {
                Chunk chunk = new Chunk();
                chunk.setFileId(fileId);
                chunk.setKnowledgeBaseId(kb.getId());
                chunk.setContent(textChunks.get(i));
                chunk.setChunkIndex(i);
                chunk.setVectorId(ids.get(i));
                chunk.setCollectionName(kb.getCode());
                chunkRepository.insert(chunk);
            }

            // 5. 更新文件状态
            fileInfo.setChunkCount(textChunks.size());
            fileInfo.setStatus(1);
            fileInfoRepository.updateById(fileInfo);
            log.info("重新解析完成: fileId={}, chunks={}", fileId, textChunks.size());

        } catch (Exception e) {
            fileInfo.setStatus(2);
            fileInfoRepository.updateById(fileInfo);
            log.error("重新解析失败: fileId={}", fileId, e);
            throw new RuntimeException("重新解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateKbConfig(String kbCode, KbConfigRequest request) {
        KnowledgeBase kb = findKbByCode(kbCode);
        if (request.getChunkSize() != null) {
            kb.setChunkSize(request.getChunkSize());
        }
        if (request.getChunkOverlap() != null) {
            kb.setChunkOverlap(request.getChunkOverlap());
        }
        if (request.getSplitType() != null) {
            kb.setSplitType(request.getSplitType());
        }
        knowledgeBaseRepository.updateById(kb);
        log.info("更新知识库配置: code={}, chunkSize={}, overlap={}, splitType={}",
                kbCode, kb.getChunkSize(), kb.getChunkOverlap(), kb.getSplitType());
    }

    @Override
    public RetrievalTestResponse retrievalTest(RetrievalTestRequest request) {
        long start = System.currentTimeMillis();
        int topK = request.getTopK() != null ? request.getTopK() : defaultTopK;
        float threshold = request.getScoreThreshold() != null ? request.getScoreThreshold() : defaultScoreThreshold;

        List<Float> queryVector = embeddingService.embed(request.getQuery());
        List<KnowledgeBase> knowledgeBases = resolveKnowledgeBases(request.getKnowledgeBaseCodes());

        List<RetrievalTestResponse.RetrievalItem> allItems = new ArrayList<>();
        for (KnowledgeBase kb : knowledgeBases) {
            try {
                List<VectorSearchResult> results = vectorService.search(
                        VectorSearchRequest.builder()
                                .collectionName(kb.getCode())
                                .queryVector(queryVector)
                                .topK(topK)
                                .outputFields(List.of("id", "file_id", "content"))
                                .build());

                for (VectorSearchResult sr : results) {
                    if (sr.getScore() >= threshold) {
                        allItems.add(RetrievalTestResponse.RetrievalItem.builder()
                                .chunkId(sr.getId())
                                .fileId(String.valueOf(sr.getFields().get("file_id")))
                                .content(String.valueOf(sr.getFields().get("content")))
                                .score(sr.getScore())
                                .knowledgeBaseCode(kb.getCode())
                                .build());
                    }
                }
            } catch (Exception e) {
                log.warn("检索知识库 {} 失败: {}", kb.getCode(), e.getMessage());
            }
        }

        // 按分数降序排列，取 topK
        allItems.sort(Comparator.comparingDouble(RetrievalTestResponse.RetrievalItem::getScore).reversed());
        List<RetrievalTestResponse.RetrievalItem> topItems = allItems.stream()
                .limit(topK).collect(Collectors.toList());

        // 补充文件名和chunkIndex
        enrichRetrievalItems(topItems);

        long costMs = System.currentTimeMillis() - start;
        return RetrievalTestResponse.builder()
                .query(request.getQuery())
                .totalResults(topItems.size())
                .costMs(costMs)
                .items(topItems)
                .build();
    }

    // ==================== 私有辅助方法 ====================

    private KnowledgeBase findKbByCode(String code) {
        KnowledgeBase kb = knowledgeBaseRepository.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getCode, code));
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在: " + code);
        }
        return kb;
    }

    /**
     * 将 splitType 枚举值映射到策略工厂可识别的策略名称
     */
    private String mapSplitType(String splitType) {
        if (splitType == null) return "fixed_length";
        return switch (splitType.toUpperCase()) {
            case "PARAGRAPH" -> "paragraph";
            case "SEMANTIC" -> "semantic";
            default -> "fixed_length";
        };
    }

    /**
     * 为检索结果项补充文件名和chunkIndex
     */
    private void enrichRetrievalItems(List<RetrievalTestResponse.RetrievalItem> items) {
        if (items == null || items.isEmpty()) return;
        Set<String> fileIds = items.stream()
                .map(RetrievalTestResponse.RetrievalItem::getFileId)
                .collect(Collectors.toSet());
        List<FileInfo> files = fileInfoRepository.selectList(
                new LambdaQueryWrapper<FileInfo>().in(FileInfo::getFileId, fileIds));
        Map<String, String> fileNameMap = files.stream()
                .collect(Collectors.toMap(FileInfo::getFileId, FileInfo::getFileName, (a, b) -> a));

        // 从 chunkId 中提取 chunkIndex（格式: fileId_chunk_N）
        items.forEach(item -> {
            item.setFileName(fileNameMap.getOrDefault(item.getFileId(), "未知文件"));
            String chunkId = item.getChunkId();
            if (chunkId != null && chunkId.contains("_chunk_")) {
                try {
                    String indexStr = chunkId.substring(chunkId.lastIndexOf("_chunk_") + 7);
                    item.setChunkIndex(Integer.parseInt(indexStr));
                } catch (NumberFormatException e) {
                    item.setChunkIndex(null);
                }
            }
        });
    }
}
