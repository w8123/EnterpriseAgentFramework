package com.enterprise.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.enterprise.ai.domain.dto.KbConfigRequest;
import com.enterprise.ai.domain.dto.KnowledgeBaseRequest;
import com.enterprise.ai.embedding.EmbeddingService;
import com.enterprise.ai.embedding.EmbeddingDimensions;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.repository.KnowledgeBaseLookup;
import com.enterprise.ai.repository.KnowledgeBaseRepository;
import com.enterprise.ai.vector.VectorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

import static com.enterprise.ai.domain.KnowledgeBaseSettings.requireEmbeddingModelInstanceId;
import static com.enterprise.ai.domain.KnowledgeBaseSettings.requireLlmModelInstanceId;
import static com.enterprise.ai.domain.KnowledgeBaseSettings.requireVectorCollectionName;
import static com.enterprise.ai.domain.KnowledgeBaseSettings.normalizeSearchMode;

@Service
public class KnowledgeBaseLifecycleService {
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseLookup lookup;
    private final KnowledgeFileDeletionService files;
    private final KnowledgeCollectionLifecycleStore collections;
    private final VectorService vectors;
    private final EmbeddingService embeddings;
    private final TransactionTemplate suspended;
    private final TransactionTemplate metadata;
    @Value("${rag.default-top-k:5}")
    private int defaultTopK = 5;
    @Value("${rag.score-threshold:0.5}")
    private float defaultScoreThreshold = 0.5f;
    private com.enterprise.ai.external.ExternalKnowledgeService external;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setExternalKnowledgeService(com.enterprise.ai.external.ExternalKnowledgeService external) {
        this.external = external;
    }

    public KnowledgeBaseLifecycleService(KnowledgeBaseRepository bases,
                                         KnowledgeFileDeletionService files,
                                         KnowledgeCollectionLifecycleStore collections,
                                         VectorService vectors,
                                         EmbeddingService embeddings,
                                         PlatformTransactionManager manager) {
        this.knowledgeBaseRepository = bases;
        this.lookup = new KnowledgeBaseLookup(bases);
        this.files = files;
        this.collections = collections;
        this.vectors = vectors;
        this.embeddings = embeddings;
        suspended = new TransactionTemplate(manager);
        suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        metadata = new TransactionTemplate(manager);
    }

    public void create(KnowledgeBaseRequest request) {
        if (com.enterprise.ai.domain.KnowledgeBaseSettings.SOURCE_EXTERNAL.equalsIgnoreCase(request.getSourceType())) {
            createExternal(request);
            return;
        }
        if (request.getEmbeddingModelInstanceId() == null || request.getEmbeddingModelInstanceId().isBlank()) {
            throw new IllegalArgumentException("请选择向量模型；还没有的话先到模型中心添加一个向量模型");
        }
        if (request.getLlmModelInstanceId() == null || request.getLlmModelInstanceId().isBlank()) {
            throw new IllegalArgumentException("请选择对话模型；还没有的话先到模型中心添加一个对话模型");
        }
        suspended.executeWithoutResult(ignored -> {
            KnowledgeBase existing = knowledgeBaseRepository.selectOne(
                    new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getCode, request.getCode()));
            if (existing != null) {
                throw new IllegalArgumentException("知识库编码已存在: " + request.getCode());
            }
            KnowledgeBase kb = new KnowledgeBase();
            kb.setName(request.getName());
            kb.setCode(request.getCode());
            kb.setVectorCollectionName("reachai_kb_" + UUID.randomUUID().toString().replace("-", ""));
            kb.setDescription(request.getDescription());
            String embeddingModelInstanceId = requireEmbeddingModelInstanceId(request.getEmbeddingModelInstanceId(), request.getCode());
            kb.setEmbeddingModelInstanceId(embeddingModelInstanceId);
            kb.setRerankModelInstanceId(trimToNull(request.getRerankModelInstanceId()));
            kb.setLlmModelInstanceId(requireLlmModelInstanceId(request.getLlmModelInstanceId(), request.getCode()));
            kb.setWorkspaceId(defaultString(request.getWorkspaceId(), "default"));
            kb.setProjectCode(request.getProjectCode());
            kb.setScope(defaultString(request.getScope(), "WORKSPACE"));
            kb.setAccessMode(normalizeAccessMode(request.getAccessMode(), ACCESS_MODE_ALL_CALLERS));
            kb.setDimension(EmbeddingDimensions.resolve(request.getDimension(), embeddingModelInstanceId, embeddings));
            kb.setChunkSize(500);
            kb.setChunkOverlap(50);
            kb.setSplitType("FIXED");
            kb.setSearchMode(defaultString(request.getSearchMode(), "hybrid"));
            kb.setTopK(request.getTopK() != null ? request.getTopK() : defaultTopK);
            kb.setSimilarityThreshold(request.getSimilarityThreshold() != null ? request.getSimilarityThreshold() : defaultScoreThreshold);
            kb.setRerankEnabled(request.getRerankEnabled() != null ? request.getRerankEnabled() : Boolean.TRUE);
            kb.setVectorWeight(request.getVectorWeight() != null ? request.getVectorWeight() : 0.7f);
            kb.setKeywordWeight(request.getKeywordWeight() != null ? request.getKeywordWeight() : 0.3f);
            kb.setStatus(1);
            kb.setSourceType(com.enterprise.ai.domain.KnowledgeBaseSettings.SOURCE_LOCAL);

            collections.register(kb);
            try {
                vectors.ensureCollection(kb.getVectorCollectionName(), kb.getDimension());
                collections.acknowledge(kb.getVectorCollectionName());
                collections.publish(kb);
            } catch (RuntimeException failure) {
                try {
                    collections.abandon(kb.getVectorCollectionName());
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        });
    }

    /**
     * An external knowledge base has no vector collection and no files: retrieval calls the external system. It still
     * gets a unique, never-created collection identity because the row's identity checks and uniqueness rely on it.
     * It is always readable by every trusted caller (per-file grants cannot exist for content ReachAI never stores).
     */
    private void createExternal(KnowledgeBaseRequest request) {
        if (external == null) throw new IllegalStateException("外部知识库功能未启用");
        var connection = external.newConnection(request.getExternal());
        metadata.executeWithoutResult(ignored -> {
            KnowledgeBase existing = knowledgeBaseRepository.selectOne(
                    new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getCode, request.getCode()));
            if (existing != null) {
                throw new IllegalArgumentException("知识库编码已存在: " + request.getCode());
            }
            KnowledgeBase kb = new KnowledgeBase();
            kb.setName(request.getName());
            kb.setCode(request.getCode());
            kb.setVectorCollectionName("reachai_ext_" + UUID.randomUUID().toString().replace("-", ""));
            kb.setDescription(request.getDescription());
            kb.setLlmModelInstanceId(trimToNull(request.getLlmModelInstanceId()));
            kb.setRerankModelInstanceId(trimToNull(request.getRerankModelInstanceId()));
            kb.setWorkspaceId(defaultString(request.getWorkspaceId(), "default"));
            kb.setProjectCode(request.getProjectCode());
            kb.setScope(defaultString(request.getScope(), "WORKSPACE"));
            kb.setAccessMode(ACCESS_MODE_ALL_CALLERS);
            kb.setDimension(0);
            kb.setChunkSize(500);
            kb.setChunkOverlap(50);
            kb.setSplitType("FIXED");
            kb.setSearchMode("vector");
            kb.setTopK(request.getTopK() != null ? request.getTopK() : defaultTopK);
            kb.setSimilarityThreshold(request.getSimilarityThreshold() != null ? request.getSimilarityThreshold() : 0.2f);
            kb.setRerankEnabled(Boolean.FALSE);
            kb.setVectorWeight(1f);
            kb.setKeywordWeight(0f);
            kb.setStatus(1);
            external.apply(kb, connection);
            if (knowledgeBaseRepository.insert(kb) != 1) throw new IllegalStateException("无法保存外部知识库");
        });
    }

    public void update(KnowledgeBaseRequest request) {
        metadata.executeWithoutResult(ignored -> {
            KnowledgeBase current = lookup.requireByCode(request.getCode());
            KnowledgeBase change = new KnowledgeBase();
            change.setName(request.getName());
            change.setDescription(request.getDescription());
            if (request.getEmbeddingModelInstanceId() != null) {
                change.setEmbeddingModelInstanceId(requireEmbeddingModelInstanceId(request.getEmbeddingModelInstanceId(), current.getCode()));
            }
            if (request.getLlmModelInstanceId() != null) {
                change.setLlmModelInstanceId(requireLlmModelInstanceId(request.getLlmModelInstanceId(), current.getCode()));
            }
            if (request.getRerankModelInstanceId() != null) {
                change.setRerankModelInstanceId(trimToNull(request.getRerankModelInstanceId()));
            }
            change.setWorkspaceId(request.getWorkspaceId());
            change.setProjectCode(request.getProjectCode());
            change.setScope(request.getScope());
            if (request.getAccessMode() != null) {
                change.setAccessMode(normalizeAccessMode(request.getAccessMode(), current.getAccessMode()));
                requireAccessModeFor(current, change.getAccessMode());
            }
            applySearchConfig(change, current.getSearchMode(), request.getSearchMode(), request.getTopK(),
                    request.getSimilarityThreshold(),
                    request.getRerankEnabled(), request.getVectorWeight(), request.getKeywordWeight());
            updateCurrent(current, change);
        });
    }

    public void updateConfig(String code, KbConfigRequest request) {
        metadata.executeWithoutResult(ignored -> {
            KnowledgeBase current = lookup.requireByCode(code);
            KnowledgeBase change = new KnowledgeBase();
            change.setChunkSize(request.getChunkSize());
            change.setChunkOverlap(request.getChunkOverlap());
            change.setSplitType(request.getSplitType());
            if (request.getAccessMode() != null) {
                change.setAccessMode(normalizeAccessMode(request.getAccessMode(), current.getAccessMode()));
                requireAccessModeFor(current, change.getAccessMode());
            }
            applySearchConfig(change, current.getSearchMode(), request.getSearchMode(), request.getTopK(),
                    request.getSimilarityThreshold(),
                    request.getRerankEnabled(), request.getVectorWeight(), request.getKeywordWeight());
            updateCurrent(current, change);
        });
    }

    private void updateCurrent(KnowledgeBase expected, KnowledgeBase change) {
        // Only the command's supplied fields are non-null. Never write a previously read row back.
        if (knowledgeBaseRepository.update(change, new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, expected.getId())
                .eq(KnowledgeBase::getCode, expected.getCode())
                .eq(KnowledgeBase::getVectorCollectionName, requireVectorCollectionName(expected))) != 1) {
            throw new IllegalStateException("知识库已删除或身份已变化");
        }
    }

    private void applySearchConfig(KnowledgeBase change, String fallbackMode, String searchMode,
                                   Integer topK, Float similarityThreshold, Boolean rerankEnabled,
                                   Float vectorWeight, Float keywordWeight) {
        if (searchMode != null) change.setSearchMode(normalizeSearchMode(searchMode, fallbackMode));
        change.setTopK(topK);
        change.setSimilarityThreshold(similarityThreshold);
        change.setRerankEnabled(rerankEnabled);
        change.setVectorWeight(vectorWeight);
        change.setKeywordWeight(keywordWeight);
    }

    static final String ACCESS_MODE_ALL_CALLERS = "ALL_CALLERS";
    static final String ACCESS_MODE_FILE_GRANTS = "FILE_GRANTS";

    private static void requireAccessModeFor(KnowledgeBase current, String mode) {
        if (com.enterprise.ai.domain.KnowledgeBaseSettings.isExternal(current) && ACCESS_MODE_FILE_GRANTS.equals(mode)) {
            throw new IllegalArgumentException("外部知识库只能设为「所有调用方可检索」");
        }
    }

    static String normalizeAccessMode(String requested, String fallback) {
        String value = requested == null ? null : requested.trim().toUpperCase(java.util.Locale.ROOT);
        if (ACCESS_MODE_ALL_CALLERS.equals(value) || ACCESS_MODE_FILE_GRANTS.equals(value)) return value;
        if (value != null && !value.isEmpty()) throw new IllegalArgumentException("谁能检索只能是 ALL_CALLERS 或 FILE_GRANTS");
        return fallback == null ? ACCESS_MODE_ALL_CALLERS : fallback;
    }

    public void deleteByCode(String code) {
        metadata.executeWithoutResult(ignored -> {
            var expected = lookup.requireByCode(code);
            var kb = knowledgeBaseRepository.lockById(expected.getId());
            if (kb == null || !Objects.equals(kb.getCode(), code)
                    || !Objects.equals(kb.getVectorCollectionName(), expected.getVectorCollectionName())) {
                throw new IllegalStateException("原知识库身份已失效");
            }
            files.deleteAllInKnowledgeBase(kb);
            // An external base never had a vector collection to reclaim.
            if (!com.enterprise.ai.domain.KnowledgeBaseSettings.isExternal(kb)) collections.retire(kb);
            if (knowledgeBaseRepository.deleteById(kb.getId()) != 1) {
                throw new IllegalStateException("无法删除知识库");
            }
        });
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
