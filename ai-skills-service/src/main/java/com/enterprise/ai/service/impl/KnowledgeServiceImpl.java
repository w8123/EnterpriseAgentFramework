package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.domain.dto.*;
import com.enterprise.ai.domain.entity.Chunk;
import com.enterprise.ai.domain.entity.FileInfo;
import com.enterprise.ai.domain.entity.KnowledgeHitLog;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.domain.entity.KnowledgeQuestion;
import com.enterprise.ai.domain.entity.KnowledgeTag;
import com.enterprise.ai.domain.vo.SimilarItem;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.pipeline.chunk.ChunkStrategy;
import com.enterprise.ai.pipeline.chunk.ChunkStrategyFactory;
import com.enterprise.ai.pipeline.parser.DocumentParser;
import com.enterprise.ai.pipeline.parser.DocumentParserFactory;
import com.enterprise.ai.repository.ChunkRepository;
import com.enterprise.ai.repository.FileInfoRepository;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import com.enterprise.ai.repository.KnowledgeHitLogRepository;
import com.enterprise.ai.repository.KnowledgeQuestionRepository;
import com.enterprise.ai.repository.KnowledgeTagRepository;
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
    private final KnowledgeTagRepository knowledgeTagRepository;
    private final KnowledgeQuestionRepository knowledgeQuestionRepository;
    private final KnowledgeHitLogRepository knowledgeHitLogRepository;
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
            chunk.setHitCount(0);
            chunk.setEnabled(1);
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
        kb.setWorkspaceId(defaultString(request.getWorkspaceId(), "default"));
        kb.setProjectCode(request.getProjectCode());
        kb.setScope(defaultString(request.getScope(), "WORKSPACE"));
        kb.setDimension(request.getDimension() != null ? request.getDimension() : dimension);
        kb.setChunkSize(500);
        kb.setChunkOverlap(50);
        kb.setSplitType("FIXED");
        kb.setSearchMode(defaultString(request.getSearchMode(), "hybrid"));
        kb.setTopK(request.getTopK() != null ? request.getTopK() : defaultTopK);
        kb.setSimilarityThreshold(request.getSimilarityThreshold() != null ? request.getSimilarityThreshold() : defaultScoreThreshold);
        kb.setDirectReturnEnabled(request.getDirectReturnEnabled() != null ? request.getDirectReturnEnabled() : Boolean.TRUE);
        kb.setDirectReturnThreshold(request.getDirectReturnThreshold() != null ? request.getDirectReturnThreshold() : 0.9f);
        kb.setRerankEnabled(request.getRerankEnabled() != null ? request.getRerankEnabled() : Boolean.TRUE);
        kb.setVectorWeight(request.getVectorWeight() != null ? request.getVectorWeight() : 0.7f);
        kb.setKeywordWeight(request.getKeywordWeight() != null ? request.getKeywordWeight() : 0.3f);
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
        if (request.getWorkspaceId() != null) kb.setWorkspaceId(request.getWorkspaceId());
        if (request.getProjectCode() != null) kb.setProjectCode(request.getProjectCode());
        if (request.getScope() != null) kb.setScope(request.getScope());
        applySearchConfig(kb, request.getSearchMode(), request.getTopK(), request.getSimilarityThreshold(),
                request.getDirectReturnEnabled(), request.getDirectReturnThreshold(), request.getRerankEnabled(),
                request.getVectorWeight(), request.getKeywordWeight());
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
        return chunks.stream().map(this::toChunkVO).collect(Collectors.toList());
    }

    @Override
    public List<ChunkVO> listChunks(String kbCode, String keyword, Integer enabled, String tagKey, String tagValue, Integer limit) {
        KnowledgeBase kb = findKbByCode(kbCode);
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
        return chunkRepository.selectList(query).stream().map(this::toChunkVO).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChunkVO updateChunk(Long chunkId, ChunkUpdateRequest request) {
        Chunk chunk = findChunk(chunkId);
        if (request.getTitle() != null) {
            chunk.setTitle(request.getTitle());
        }
        if (request.getContent() != null) {
            if (request.getContent().isBlank()) {
                throw new IllegalArgumentException("chunk content cannot be blank");
            }
            chunk.setContent(request.getContent());
        }
        if (request.getEnabled() != null) {
            chunk.setEnabled(request.getEnabled() == 0 ? 0 : 1);
        }
        chunkRepository.updateById(chunk);
        return toChunkVO(chunk);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChunkVO toggleChunk(Long chunkId, Integer enabled) {
        Chunk chunk = findChunk(chunkId);
        chunk.setEnabled(enabled != null && enabled == 0 ? 0 : 1);
        chunkRepository.updateById(chunk);
        return toChunkVO(chunk);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reembedChunk(Long chunkId) {
        Chunk chunk = findChunk(chunkId);
        KnowledgeBase kb = knowledgeBaseRepository.selectById(chunk.getKnowledgeBaseId());
        if (kb == null) {
            throw new IllegalArgumentException("knowledge base not found for chunk: " + chunkId);
        }
        String vectorId = chunk.getVectorId();
        if (vectorId == null || vectorId.isBlank()) {
            vectorId = chunk.getFileId() + "_chunk_" + chunk.getChunkIndex();
            chunk.setVectorId(vectorId);
        }
        vectorService.ensureCollection(kb.getCode(), kb.getDimension() != null ? kb.getDimension() : dimension);
        try {
            vectorService.deleteById(kb.getCode(), vectorId);
        } catch (Exception e) {
            log.warn("Delete old vector failed, will insert replacement. vectorId={}, error={}", vectorId, e.getMessage());
        }
        List<Float> vector = embeddingService.embed(chunk.getContent());
        vectorService.insert(kb.getCode(), List.of(vectorId), List.of(vector), List.of(chunk.getFileId()), List.of(chunk.getContent()));
        chunk.setCollectionName(kb.getCode());
        chunkRepository.updateById(chunk);
    }

    @Override
    public List<KnowledgeHitLogDTO> listHitLogs(String kbCode, Integer limit, Boolean lowConfidenceOnly) {
        KnowledgeBase kb = findKbByCode(kbCode);
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

    @Override
    public KnowledgeOpsDashboardVO getOpsDashboard(String kbCode) {
        KnowledgeBase kb = findKbByCode(kbCode);
        List<FileInfo> recentFiles = fileInfoRepository.selectList(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getKnowledgeBaseId, kb.getId())
                .orderByDesc(FileInfo::getCreateTime)
                .last("LIMIT 8"));
        List<ChunkVO> hotChunks = chunkRepository.selectList(new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getKnowledgeBaseId, kb.getId())
                        .orderByDesc(Chunk::getHitCount)
                        .last("LIMIT 8"))
                .stream().map(this::toChunkVO).collect(Collectors.toList());
        List<ChunkVO> zeroHitChunks = chunkRepository.selectList(new LambdaQueryWrapper<Chunk>()
                        .eq(Chunk::getKnowledgeBaseId, kb.getId())
                        .and(w -> w.eq(Chunk::getHitCount, 0).or().isNull(Chunk::getHitCount))
                        .orderByDesc(Chunk::getCreateTime)
                        .last("LIMIT 8"))
                .stream().map(this::toChunkVO).collect(Collectors.toList());
        return KnowledgeOpsDashboardVO.builder()
                .stats(getStats(kbCode))
                .recentFiles(recentFiles.stream().map(this::toPipelineFileStatus).collect(Collectors.toList()))
                .hotChunks(hotChunks)
                .zeroHitChunks(zeroHitChunks)
                .recentHits(listHitLogs(kbCode, 8, false))
                .lowConfidenceHits(listHitLogs(kbCode, 8, true))
                .build();
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
                chunk.setHitCount(0);
                chunk.setEnabled(1);
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
        applySearchConfig(kb, request.getSearchMode(), request.getTopK(), request.getSimilarityThreshold(),
                request.getDirectReturnEnabled(), request.getDirectReturnThreshold(), request.getRerankEnabled(),
                request.getVectorWeight(), request.getKeywordWeight());
        knowledgeBaseRepository.updateById(kb);
        log.info("更新知识库配置: code={}, chunkSize={}, overlap={}, splitType={}",
                kbCode, kb.getChunkSize(), kb.getChunkOverlap(), kb.getSplitType());
    }

    @Override
    public RetrievalTestResponse retrievalTest(RetrievalTestRequest request) {
        long start = System.currentTimeMillis();
        int topK = request.getTopK() != null ? request.getTopK() : defaultTopK;
        float threshold = request.getScoreThreshold() != null ? request.getScoreThreshold() : defaultScoreThreshold;
        String searchMode = normalizeSearchMode(request.getSearchMode(), "hybrid");

        List<Float> queryVector = !"keyword".equals(searchMode) ? embeddingService.embed(request.getQuery()) : List.of();
        List<KnowledgeBase> knowledgeBases = resolveKnowledgeBases(request.getKnowledgeBaseCodes());

        List<RetrievalTestResponse.RetrievalItem> allItems = new ArrayList<>();
        for (KnowledgeBase kb : knowledgeBases) {
            try {
                if (!"keyword".equals(searchMode)) {
                    List<VectorSearchResult> results = vectorService.search(
                            VectorSearchRequest.builder()
                                    .collectionName(kb.getCode())
                                    .queryVector(queryVector)
                                    .topK(topK)
                                    .outputFields(List.of("id", "file_id", "content"))
                                    .build());

                    for (VectorSearchResult sr : results) {
                        if (sr.getScore() >= threshold) {
                            Chunk chunk = findChunkByVectorIdOrFields(kb.getId(), sr.getId(), sr.getFields());
                            if (chunk != null && chunk.getEnabled() != null && chunk.getEnabled() == 0) {
                                continue;
                            }
                            RetrievalTestResponse.RetrievalItem item = chunk != null
                                    ? buildRetrievalItem(kb, chunk)
                                    : RetrievalTestResponse.RetrievalItem.builder()
                                            .chunkId(sr.getId())
                                            .fileId(String.valueOf(sr.getFields().get("file_id")))
                                            .content(String.valueOf(sr.getFields().get("content")))
                                            .knowledgeBaseCode(kb.getCode())
                                            .build();
                            item.setVectorScore(sr.getScore());
                            item.setScore(sr.getScore());
                            item.setReason("vector");
                            allItems.add(item);
                        }
                    }
                }
                if (!"vector".equals(searchMode)) {
                    for (Chunk chunk : keywordSearch(kb.getId(), request.getQuery(), topK * 3)) {
                        float keywordScore = keywordScore(request.getQuery(), chunk.getContent());
                        if (keywordScore >= threshold) {
                            RetrievalTestResponse.RetrievalItem item = buildRetrievalItem(kb, chunk);
                            item.setKeywordScore(keywordScore);
                            item.setScore(keywordScore);
                            item.setReason("keyword");
                            allItems.add(item);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("检索知识库 {} 失败: {}", kb.getCode(), e.getMessage());
            }
        }

        // 按分数降序排列，取 topK
        Map<String, KnowledgeBase> kbMap = knowledgeBases.stream()
                .collect(Collectors.toMap(KnowledgeBase::getCode, kb -> kb, (a, b) -> a));
        Map<String, RetrievalTestResponse.RetrievalItem> merged = new LinkedHashMap<>();
        for (RetrievalTestResponse.RetrievalItem item : allItems) {
            String key = item.getChunkDbId() != null ? "db:" + item.getChunkDbId() : "vec:" + item.getChunkId();
            RetrievalTestResponse.RetrievalItem existing = merged.get(key);
            if (existing == null) {
                merged.put(key, item);
            } else {
                if (item.getVectorScore() != null) existing.setVectorScore(item.getVectorScore());
                if (item.getKeywordScore() != null) existing.setKeywordScore(item.getKeywordScore());
            }
        }
        allItems = new ArrayList<>(merged.values());
        for (RetrievalTestResponse.RetrievalItem item : allItems) {
            KnowledgeBase kb = kbMap.get(item.getKnowledgeBaseCode());
            float vectorWeight = request.getVectorWeight() != null ? request.getVectorWeight()
                    : (kb != null ? valueOrDefault(kb.getVectorWeight(), 0.7f) : 0.7f);
            float keywordWeight = request.getKeywordWeight() != null ? request.getKeywordWeight()
                    : (kb != null ? valueOrDefault(kb.getKeywordWeight(), 0.3f) : 0.3f);
            boolean useRerank = request.getRerankEnabled() != null ? request.getRerankEnabled()
                    : kb == null || Boolean.TRUE.equals(kb.getRerankEnabled());
            scoreItem(request, kb, searchMode, vectorWeight, keywordWeight, useRerank, item);
        }
        allItems = allItems.stream()
                .filter(item -> item.getScore() != null && item.getScore() >= threshold)
                .collect(Collectors.toList());

        allItems.sort(Comparator.comparingDouble(RetrievalTestResponse.RetrievalItem::getScore).reversed());
        List<RetrievalTestResponse.RetrievalItem> topItems = allItems.stream()
                .limit(topK).collect(Collectors.toList());

        // 补充文件名和chunkIndex
        enrichRetrievalItems(topItems);
        if (Boolean.TRUE.equals(request.getRecordHit())) {
            if (topItems.isEmpty()) {
                recordMisses(request, knowledgeBases);
            } else {
                recordHits(request, topItems);
            }
        }
        RetrievalTestResponse.RetrievalItem direct = topItems.stream()
                .filter(item -> Boolean.TRUE.equals(item.getDirectReturn()))
                .findFirst()
                .orElse(null);

        long costMs = System.currentTimeMillis() - start;
        return RetrievalTestResponse.builder()
                .query(request.getQuery())
                .searchMode(searchMode)
                .totalResults(topItems.size())
                .costMs(costMs)
                .directReturn(direct != null)
                .directReturnContent(direct != null ? direct.getContent() : null)
                .items(topItems)
                .build();
    }

    // ==================== 私有辅助方法 ====================

    @Override
    public KnowledgeStatsVO getStats(String kbCode) {
        KnowledgeBase kb = findKbByCode(kbCode);
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

    @Override
    public List<KnowledgeTagDTO> listTags(String kbCode, String targetType, String targetId) {
        KnowledgeBase kb = findKbByCode(kbCode);
        LambdaQueryWrapper<KnowledgeTag> query = new LambdaQueryWrapper<KnowledgeTag>()
                .eq(KnowledgeTag::getKnowledgeBaseId, kb.getId())
                .orderByAsc(KnowledgeTag::getTagGroup)
                .orderByAsc(KnowledgeTag::getSortOrder)
                .orderByDesc(KnowledgeTag::getCreateTime);
        if (targetType != null && !targetType.isBlank()) {
            query.eq(KnowledgeTag::getTargetType, targetType);
        }
        if (targetId != null && !targetId.isBlank()) {
            query.eq(KnowledgeTag::getTargetId, targetId);
        }
        return knowledgeTagRepository.selectList(query).stream().map(this::toTagDTO).collect(Collectors.toList());
    }

    @Override
    public List<KnowledgeTagStatsDTO> listTagStats(String kbCode) {
        KnowledgeBase kb = findKbByCode(kbCode);
        List<KnowledgeTag> tags = knowledgeTagRepository.selectList(new LambdaQueryWrapper<KnowledgeTag>()
                .eq(KnowledgeTag::getKnowledgeBaseId, kb.getId()));
        Map<String, List<KnowledgeTag>> grouped = tags.stream()
                .collect(Collectors.groupingBy(tag -> tag.getTagKey() + "\u0001" + tag.getTagValue()));
        return grouped.values().stream()
                .map(items -> {
                    KnowledgeTag first = items.get(0);
                    return KnowledgeTagStatsDTO.builder()
                            .tagKey(first.getTagKey())
                            .tagValue(first.getTagValue())
                            .tagGroup(first.getTagGroup())
                            .color(first.getColor())
                            .description(first.getDescription())
                            .parentId(first.getParentId())
                            .sortOrder(first.getSortOrder())
                            .totalCount(items.size())
                            .knowledgeCount(countTagTargets(items, "KNOWLEDGE"))
                            .fileCount(countTagTargets(items, "FILE"))
                            .chunkCount(countTagTargets(items, "CHUNK"))
                            .build();
                })
                .sorted(Comparator
                        .comparing((KnowledgeTagStatsDTO item) -> defaultString(item.getTagGroup(), "默认"))
                        .thenComparing(item -> item.getSortOrder() != null ? item.getSortOrder() : 0)
                        .thenComparing(KnowledgeTagStatsDTO::getTagKey)
                        .thenComparing(KnowledgeTagStatsDTO::getTagValue))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeTagDTO createTag(String kbCode, KnowledgeTagRequest request) {
        KnowledgeBase kb = findKbByCode(kbCode);
        KnowledgeTag tag = new KnowledgeTag();
        tag.setKnowledgeBaseId(kb.getId());
        tag.setTargetType(defaultString(request.getTargetType(), "KNOWLEDGE"));
        tag.setTargetId(request.getTargetId());
        tag.setTagKey(request.getTagKey());
        tag.setTagValue(request.getTagValue());
        tag.setTagGroup(defaultString(request.getTagGroup(), "默认"));
        tag.setColor(defaultString(request.getColor(), "#409EFF"));
        tag.setDescription(request.getDescription());
        tag.setParentId(request.getParentId());
        tag.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        knowledgeTagRepository.insert(tag);
        return toTagDTO(tag);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<KnowledgeTagDTO> batchCreateTags(String kbCode, KnowledgeTagBatchRequest request) {
        KnowledgeBase kb = findKbByCode(kbCode);
        String targetType = defaultString(request.getTargetType(), "").toUpperCase(Locale.ROOT);
        if (!"FILE".equals(targetType) && !"CHUNK".equals(targetType)) {
            throw new IllegalArgumentException("batch tag only supports FILE or CHUNK targets");
        }
        List<String> targetIds = request.getTargetIds().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .distinct()
                .collect(Collectors.toList());
        if (targetIds.isEmpty()) {
            throw new IllegalArgumentException("targetIds cannot be empty");
        }
        validateTagTargets(kb.getId(), targetType, targetIds);

        List<KnowledgeTagDTO> created = new ArrayList<>();
        for (String targetId : targetIds) {
            Long exists = knowledgeTagRepository.selectCount(new LambdaQueryWrapper<KnowledgeTag>()
                    .eq(KnowledgeTag::getKnowledgeBaseId, kb.getId())
                    .eq(KnowledgeTag::getTargetType, targetType)
                    .eq(KnowledgeTag::getTargetId, targetId)
                    .eq(KnowledgeTag::getTagKey, request.getTagKey())
                    .eq(KnowledgeTag::getTagValue, request.getTagValue()));
            if (exists != null && exists > 0) {
                continue;
            }
            KnowledgeTag tag = new KnowledgeTag();
            tag.setKnowledgeBaseId(kb.getId());
            tag.setTargetType(targetType);
            tag.setTargetId(targetId);
            tag.setTagKey(request.getTagKey());
            tag.setTagValue(request.getTagValue());
            tag.setTagGroup(defaultString(request.getTagGroup(), "默认"));
            tag.setColor(defaultString(request.getColor(), "#409EFF"));
            tag.setDescription(request.getDescription());
            tag.setParentId(request.getParentId());
            tag.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
            knowledgeTagRepository.insert(tag);
            created.add(toTagDTO(tag));
        }
        return created;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTag(String kbCode, Long tagId) {
        KnowledgeBase kb = findKbByCode(kbCode);
        knowledgeTagRepository.delete(new LambdaQueryWrapper<KnowledgeTag>()
                .eq(KnowledgeTag::getId, tagId)
                .eq(KnowledgeTag::getKnowledgeBaseId, kb.getId()));
    }

    @Override
    public List<KnowledgeQuestionDTO> listQuestions(String kbCode, Long chunkId) {
        KnowledgeBase kb = findKbByCode(kbCode);
        LambdaQueryWrapper<KnowledgeQuestion> query = new LambdaQueryWrapper<KnowledgeQuestion>()
                .eq(KnowledgeQuestion::getKnowledgeBaseId, kb.getId())
                .orderByDesc(KnowledgeQuestion::getUpdateTime);
        if (chunkId != null) {
            query.eq(KnowledgeQuestion::getChunkId, chunkId);
        }
        return knowledgeQuestionRepository.selectList(query).stream()
                .map(this::toQuestionDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeQuestionDTO createQuestion(String kbCode, KnowledgeQuestionRequest request) {
        KnowledgeBase kb = findKbByCode(kbCode);
        if (request.getChunkId() != null) {
            Chunk chunk = chunkRepository.selectById(request.getChunkId());
            if (chunk == null || !Objects.equals(chunk.getKnowledgeBaseId(), kb.getId())) {
                throw new IllegalArgumentException("chunk does not belong to knowledge base: " + request.getChunkId());
            }
        }
        KnowledgeQuestion question = new KnowledgeQuestion();
        question.setKnowledgeBaseId(kb.getId());
        question.setChunkId(request.getChunkId());
        question.setQuestion(request.getQuestion());
        question.setSource(defaultString(request.getSource(), "MANUAL"));
        question.setHitCount(0);
        knowledgeQuestionRepository.insert(question);
        return toQuestionDTO(question);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteQuestion(String kbCode, Long questionId) {
        KnowledgeBase kb = findKbByCode(kbCode);
        knowledgeQuestionRepository.delete(new LambdaQueryWrapper<KnowledgeQuestion>()
                .eq(KnowledgeQuestion::getId, questionId)
                .eq(KnowledgeQuestion::getKnowledgeBaseId, kb.getId()));
    }

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
    private Chunk findChunk(Long chunkId) {
        Chunk chunk = chunkRepository.selectById(chunkId);
        if (chunk == null) {
            throw new IllegalArgumentException("chunk not found: " + chunkId);
        }
        return chunk;
    }

    private ChunkVO toChunkVO(Chunk c) {
        ChunkVO vo = new ChunkVO();
        BeanUtils.copyProperties(c, vo);
        vo.setLength(c.getContent() != null ? c.getContent().length() : 0);
        return vo;
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
        String finalStatus = file.getStatus() != null && file.getStatus() == 2 ? "failed"
                : file.getStatus() != null && file.getStatus() == 1 ? "done" : "running";
        List<KnowledgeOpsDashboardVO.PipelineStepStatus> steps = List.of(
                pipelineStep("PARSE", "文档解析", finalStatus),
                pipelineStep("CLEAN", "文本清洗", finalStatus),
                pipelineStep("SPLIT", "段落切分", finalStatus),
                pipelineStep("EMBEDDING", "向量生成", finalStatus),
                pipelineStep("INDEXING", "索引入库", finalStatus)
        );
        return KnowledgeOpsDashboardVO.PipelineFileStatus.builder()
                .fileId(file.getFileId())
                .fileName(file.getFileName())
                .status(file.getStatus())
                .chunkCount(file.getChunkCount())
                .steps(steps)
                .build();
    }

    private KnowledgeOpsDashboardVO.PipelineStepStatus pipelineStep(String name, String label, String status) {
        return KnowledgeOpsDashboardVO.PipelineStepStatus.builder()
                .name(name)
                .label(label)
                .status(status)
                .build();
    }

    private void validateTagTargets(Long kbId, String targetType, List<String> targetIds) {
        if ("FILE".equals(targetType)) {
            Long count = fileInfoRepository.selectCount(new LambdaQueryWrapper<FileInfo>()
                    .eq(FileInfo::getKnowledgeBaseId, kbId)
                    .in(FileInfo::getFileId, targetIds));
            if (count == null || count != targetIds.size()) {
                throw new IllegalArgumentException("some files do not belong to knowledge base");
            }
            return;
        }
        List<Long> chunkIds = targetIds.stream()
                .map(this::parseLongOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (chunkIds.size() != targetIds.size()) {
            throw new IllegalArgumentException("chunk targetIds must be numeric ids");
        }
        Long count = chunkRepository.selectCount(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getKnowledgeBaseId, kbId)
                .in(Chunk::getId, chunkIds));
        if (count == null || count != targetIds.size()) {
            throw new IllegalArgumentException("some chunks do not belong to knowledge base");
        }
    }

    private Long parseLongOrNull(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int countTagTargets(List<KnowledgeTag> tags, String targetType) {
        return (int) tags.stream()
                .filter(tag -> targetType.equals(tag.getTargetType()))
                .count();
    }

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

    private RetrievalTestResponse.RetrievalItem buildRetrievalItem(KnowledgeBase kb, Chunk chunk) {
        return RetrievalTestResponse.RetrievalItem.builder()
                .chunkDbId(chunk.getId())
                .chunkId(chunk.getVectorId())
                .fileId(chunk.getFileId())
                .content(chunk.getContent())
                .score(0f)
                .hitCount(chunk.getHitCount() != null ? chunk.getHitCount() : 0)
                .knowledgeBaseCode(kb.getCode())
                .chunkIndex(chunk.getChunkIndex())
                .build();
    }

    private Chunk findChunkByVectorIdOrFields(Long knowledgeBaseId, String vectorId, Map<String, Object> fields) {
        Chunk chunk = chunkRepository.selectOne(new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getKnowledgeBaseId, knowledgeBaseId)
                .eq(Chunk::getVectorId, vectorId)
                .last("LIMIT 1"));
        if (chunk != null) {
            return chunk;
        }
        Object fileId = fields != null ? fields.get("file_id") : null;
        Object content = fields != null ? fields.get("content") : null;
        LambdaQueryWrapper<Chunk> query = new LambdaQueryWrapper<Chunk>().eq(Chunk::getKnowledgeBaseId, knowledgeBaseId);
        if (fileId != null) {
            query.eq(Chunk::getFileId, String.valueOf(fileId));
        }
        if (content != null) {
            query.eq(Chunk::getContent, String.valueOf(content));
        }
        return chunkRepository.selectOne(query.last("LIMIT 1"));
    }

    private List<Chunk> keywordSearch(Long knowledgeBaseId, String queryText, int limit) {
        List<String> terms = tokenize(queryText);
        if (terms.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<Chunk> query = new LambdaQueryWrapper<Chunk>()
                .eq(Chunk::getKnowledgeBaseId, knowledgeBaseId)
                .ne(Chunk::getEnabled, 0)
                .and(wrapper -> {
                    for (String term : terms) {
                        wrapper.or().like(Chunk::getContent, term);
                    }
                })
                .last("LIMIT " + Math.max(1, limit));
        return chunkRepository.selectList(query);
    }

    private float keywordScore(String queryText, String content) {
        List<String> terms = tokenize(queryText);
        if (terms.isEmpty() || content == null || content.isBlank()) {
            return 0f;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        long matches = terms.stream().filter(lower::contains).count();
        return Math.min(1f, (float) matches / (float) terms.size());
    }

    private float lightweightRerankScore(String queryText, String content) {
        float keyword = keywordScore(queryText, content);
        int length = content != null ? content.length() : 0;
        float lengthScore = length > 0 && length <= 1200 ? 1f : 0.85f;
        return Math.min(1f, keyword * 0.85f + lengthScore * 0.15f);
    }

    private void scoreItem(RetrievalTestRequest request, KnowledgeBase kb, String searchMode,
                           float vectorWeight, float keywordWeight, boolean useRerank,
                           RetrievalTestResponse.RetrievalItem item) {
        float vectorScore = item.getVectorScore() != null ? item.getVectorScore() : 0f;
        float keywordScore = item.getKeywordScore() != null ? item.getKeywordScore() : 0f;
        float score = switch (searchMode) {
            case "vector" -> vectorScore;
            case "keyword" -> keywordScore;
            default -> vectorScore * vectorWeight + keywordScore * keywordWeight;
        };
        if (useRerank) {
            float rerank = lightweightRerankScore(request.getQuery(), item.getContent());
            item.setRerankScore(rerank);
            score = score * 0.8f + rerank * 0.2f;
        }
        item.setScore(score);
        item.setReason(reasonFor(item, searchMode, useRerank));
        item.setDirectReturn(isDirectReturn(request, kb, score));
    }

    private String reasonFor(RetrievalTestResponse.RetrievalItem item, String searchMode, boolean useRerank) {
        return searchMode + (useRerank ? " + rerank" : "")
                + " (vector=" + formatScore(item.getVectorScore())
                + ", keyword=" + formatScore(item.getKeywordScore()) + ")";
    }

    private String formatScore(Float score) {
        return score == null ? "0.000" : String.format(Locale.ROOT, "%.3f", score);
    }

    private boolean isDirectReturn(RetrievalTestRequest request, KnowledgeBase kb, float score) {
        boolean enabled = request.getDirectReturnEnabled() != null
                ? request.getDirectReturnEnabled()
                : kb == null || Boolean.TRUE.equals(kb.getDirectReturnEnabled());
        float threshold = request.getDirectReturnThreshold() != null
                ? request.getDirectReturnThreshold()
                : (kb != null ? valueOrDefault(kb.getDirectReturnThreshold(), 0.9f) : 0.9f);
        return enabled && score >= threshold;
    }

    private void recordHits(RetrievalTestRequest request, List<RetrievalTestResponse.RetrievalItem> items) {
        for (RetrievalTestResponse.RetrievalItem item : items) {
            if (item.getChunkDbId() == null) {
                continue;
            }
            Chunk chunk = chunkRepository.selectById(item.getChunkDbId());
            KnowledgeBase kb = findKbByCode(item.getKnowledgeBaseCode());
            if (chunk != null) {
                chunk.setHitCount((chunk.getHitCount() != null ? chunk.getHitCount() : 0) + 1);
                chunkRepository.updateById(chunk);
                item.setHitCount(chunk.getHitCount());
            }
            KnowledgeHitLog log = new KnowledgeHitLog();
            log.setKnowledgeBaseId(kb.getId());
            log.setChunkId(item.getChunkDbId());
            log.setQueryText(request.getQuery());
            log.setSearchMode(request.getSearchMode());
            log.setScore(item.getScore());
            log.setDirectReturn(Boolean.TRUE.equals(item.getDirectReturn()) ? 1 : 0);
            log.setTraceId(request.getTraceId());
            log.setUserId(request.getUserId());
            knowledgeHitLogRepository.insert(log);
        }
    }

    private void recordMisses(RetrievalTestRequest request, List<KnowledgeBase> knowledgeBases) {
        for (KnowledgeBase kb : knowledgeBases) {
            KnowledgeHitLog log = new KnowledgeHitLog();
            log.setKnowledgeBaseId(kb.getId());
            log.setQueryText(request.getQuery());
            log.setSearchMode(request.getSearchMode());
            log.setScore(0f);
            log.setDirectReturn(0);
            log.setTraceId(request.getTraceId());
            log.setUserId(request.getUserId());
            knowledgeHitLogRepository.insert(log);
        }
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[\\s,.;:!?，。；：！？、()（）\\[\\]{}]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .limit(16)
                .collect(Collectors.toList());
    }

    private String normalizeSearchMode(String requested, String fallback) {
        String value = requested != null && !requested.isBlank() ? requested : fallback;
        if (value == null || value.isBlank()) {
            return "hybrid";
        }
        value = value.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "vector", "embedding" -> "vector";
            case "keyword", "keywords" -> "keyword";
            default -> "hybrid";
        };
    }

    private void applySearchConfig(KnowledgeBase kb, String searchMode, Integer topK, Float similarityThreshold,
                                   Boolean directReturnEnabled, Float directReturnThreshold, Boolean rerankEnabled,
                                   Float vectorWeight, Float keywordWeight) {
        if (searchMode != null) kb.setSearchMode(normalizeSearchMode(searchMode, kb.getSearchMode()));
        if (topK != null) kb.setTopK(topK);
        if (similarityThreshold != null) kb.setSimilarityThreshold(similarityThreshold);
        if (directReturnEnabled != null) kb.setDirectReturnEnabled(directReturnEnabled);
        if (directReturnThreshold != null) kb.setDirectReturnThreshold(directReturnThreshold);
        if (rerankEnabled != null) kb.setRerankEnabled(rerankEnabled);
        if (vectorWeight != null) kb.setVectorWeight(vectorWeight);
        if (keywordWeight != null) kb.setKeywordWeight(keywordWeight);
    }

    private KnowledgeTagDTO toTagDTO(KnowledgeTag tag) {
        KnowledgeTagDTO dto = new KnowledgeTagDTO();
        BeanUtils.copyProperties(tag, dto);
        return dto;
    }

    private KnowledgeQuestionDTO toQuestionDTO(KnowledgeQuestion question) {
        KnowledgeQuestionDTO dto = new KnowledgeQuestionDTO();
        BeanUtils.copyProperties(question, dto);
        return dto;
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
        return chunkRepository.selectList(new LambdaQueryWrapper<Chunk>().eq(Chunk::getKnowledgeBaseId, kbId)).stream()
                .map(Chunk::getHitCount)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private int valueOrDefault(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private float valueOrDefault(Float value, float fallback) {
        return value != null ? value : fallback;
    }
}
