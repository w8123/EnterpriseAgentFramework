package com.enterprise.ai.service;

import com.enterprise.ai.domain.dto.*;
import com.enterprise.ai.domain.entity.KnowledgeBase;
import com.enterprise.ai.domain.vo.SimilarItem;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库管理服务接口
 */
public interface KnowledgeService {

    /**
     * 导入文本块到知识库（embedding + 入向量库 + 存 MySQL）
     */
    void importChunks(KnowledgeImportRequest request);

    /**
     * 根据知识库编码列表解析 KnowledgeBase 实体。
     * 若为空，返回全部已启用的知识库。
     */
    List<KnowledgeBase> resolveKnowledgeBases(List<String> codes);

    /**
     * 为 SimilarItem 列表补充 fileName
     */
    void enrichFileName(List<SimilarItem> items);

    /**
     * 按文件ID删除知识（向量 + 数据库）
     */
    void deleteByFileId(String knowledgeBaseCode, String fileId);

    // ==================== 知识库 CRUD ====================

    List<KnowledgeBaseVO> listAll();

    void create(KnowledgeBaseRequest request);

    void update(KnowledgeBaseRequest request);

    void deleteByCode(String code);

    // ==================== Chunk 预览 ====================

    ChunkPreviewResponse previewChunks(MultipartFile file, String chunkStrategy, Integer chunkSize, Integer chunkOverlap);

    // ==================== V2 新增：知识库详情/文件管理/检索测试 ====================

    /** 获取知识库下的文件列表 */
    List<FileInfoVO> getFilesByKbCode(String kbCode);

    /** 获取文件的 chunk 列表 */
    List<ChunkVO> getChunksByFileId(String fileId);

    /** 按文件ID删除（自动查找所属知识库，同时删除向量） */
    void deleteFileById(String fileId);

    /** 重新解析文件（使用知识库最新配置重新切分、向量化） */
    void reparseFile(String fileId);

    /** 更新知识库 chunk 策略配置 */
    void updateKbConfig(String kbCode, KbConfigRequest request);

    /** 检索测试（纯检索，不调用LLM） */
    RetrievalTestResponse retrievalTest(RetrievalTestRequest request);
}
