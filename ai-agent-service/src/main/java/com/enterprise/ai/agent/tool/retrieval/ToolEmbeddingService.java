package com.enterprise.ai.agent.tool.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.config.ToolRetrievalProperties;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.param.collection.CollectionSchemaParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.index.CreateIndexParam;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tool 向量索引服务：管理 {@code tool_embeddings} Milvus collection 的生命周期与增删改。
 * <p>
 * 入库文本：依次拼接非空的 {@code ai_description}、{@code description}、{@code name}（换行分隔），
 * 以便「AI 理解」与「描述」均参与向量召回；三者皆空则跳过不入库。
 * <p>
 * 任何 Milvus 异常都只记日志 + 标记「不可用」，让检索侧走降级，不阻塞主链路。
 */
@Slf4j
@Service
public class ToolEmbeddingService {

    public static final String F_ID = "tool_id";
    public static final String F_PROJECT = "project_id";
    public static final String F_MODULE = "module_id";
    public static final String F_ENABLED = "enabled";
    public static final String F_VISIBLE = "agent_visible";
    /** Phase 2.0 新增：区分 TOOL / SKILL 形态的 scalar 字段（varchar 便于未来扩展）。 */
    public static final String F_KIND = "kind";
    public static final String F_TEXT = "text";
    public static final String F_VECTOR = "embedding";

    private static final int TEXT_MAX_CHARS = 4096;
    private static final int KIND_MAX_CHARS = 16;

    private final MilvusServiceClient milvus;
    private final EmbeddingClient embeddingClient;
    private final ToolRetrievalProperties properties;
    private final ToolDefinitionMapper toolDefinitionMapper;

    private final AtomicBoolean collectionReady = new AtomicBoolean(false);

    public ToolEmbeddingService(MilvusServiceClient milvus,
                                EmbeddingClient embeddingClient,
                                ToolRetrievalProperties properties,
                                ToolDefinitionMapper toolDefinitionMapper) {
        this.milvus = milvus;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.toolDefinitionMapper = toolDefinitionMapper;
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("[ToolEmbedding] ai.tool-retrieval.enabled=false，跳过 Milvus collection 初始化");
            return;
        }
        try {
            ensureCollection();
            collectionReady.set(true);
        } catch (Exception ex) {
            log.warn("[ToolEmbedding] Milvus collection 初始化失败，Tool Retrieval 将降级到白名单模式: {}",
                    ex.toString());
        }
    }

    public boolean isReady() {
        return collectionReady.get();
    }

    // ==================== Collection 生命周期 ====================

    /**
     * 建 collection 与索引（幂等）。
     */
    public synchronized void ensureCollection() {
        String name = properties.getCollectionName();
        R<Boolean> has = milvus.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(name)
                .build());
        if (has.getData() != null && has.getData()) {
            // Phase 2.0 schema 含 F_KIND；旧 collection 没有则 drop 重建（数据由 rebuildAll 回灌）
            if (!hasKindField(name)) {
                log.warn("[ToolEmbedding] 旧 collection {} 缺失 {} 字段，drop 重建；请调用 /api/tools/retrieval/rebuild 重灌数据",
                        name, F_KIND);
                milvus.dropCollection(DropCollectionParam.newBuilder().withCollectionName(name).build());
            } else {
                milvus.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(name).build());
                return;
            }
        }

        List<FieldType> fields = List.of(
                FieldType.newBuilder().withName(F_ID).withDataType(DataType.Int64)
                        .withPrimaryKey(true).withAutoID(false).build(),
                FieldType.newBuilder().withName(F_PROJECT).withDataType(DataType.Int64).build(),
                FieldType.newBuilder().withName(F_MODULE).withDataType(DataType.Int64).build(),
                FieldType.newBuilder().withName(F_ENABLED).withDataType(DataType.Bool).build(),
                FieldType.newBuilder().withName(F_VISIBLE).withDataType(DataType.Bool).build(),
                FieldType.newBuilder().withName(F_KIND).withDataType(DataType.VarChar)
                        .withMaxLength(KIND_MAX_CHARS).build(),
                FieldType.newBuilder().withName(F_TEXT).withDataType(DataType.VarChar)
                        .withMaxLength(TEXT_MAX_CHARS).build(),
                FieldType.newBuilder().withName(F_VECTOR).withDataType(DataType.FloatVector)
                        .withDimension(properties.getEmbeddingDim()).build()
        );
        CollectionSchemaParam.Builder schemaBuilder = CollectionSchemaParam.newBuilder();
        fields.forEach(schemaBuilder::addFieldType);
        R<RpcStatus> create = milvus.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(name)
                .withSchema(schemaBuilder.build())
                .build());
        if (create.getException() != null) {
            throw new RuntimeException("创建 Milvus collection 失败: " + name, create.getException());
        }

        milvus.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(name)
                .withFieldName(F_VECTOR)
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":1024}")
                .build());

        milvus.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(name).build());
        log.info("[ToolEmbedding] collection {} 已创建并加载", name);
    }

    // ==================== 单条 upsert / delete ====================

    /**
     * 按单个 tool 做 upsert：先 delete（如果已存在）再 insert。
     * 文本为空时 → 仅 delete，不插入（避免脏数据）。
     */
    public void upsert(ToolDefinitionEntity tool) {
        if (tool == null || tool.getId() == null) {
            return;
        }
        if (!properties.isEnabled() || !collectionReady.get()) {
            return;
        }
        String text = buildText(tool);
        if (text == null || text.isBlank()) {
            delete(tool.getId());
            return;
        }
        try {
            List<Float> vector = embeddingClient.embed(text);
            delete(tool.getId());
            insert(tool, text, vector);
        } catch (Exception ex) {
            log.warn("[ToolEmbedding] upsert 失败: toolId={}, name={}, err={}",
                    tool.getId(), tool.getName(), ex.toString());
        }
    }

    public void delete(Long toolId) {
        if (toolId == null) {
            return;
        }
        if (!properties.isEnabled() || !collectionReady.get()) {
            return;
        }
        try {
            R<MutationResult> result = milvus.delete(DeleteParam.newBuilder()
                    .withCollectionName(properties.getCollectionName())
                    .withExpr(F_ID + " == " + toolId)
                    .build());
            if (result.getException() != null) {
                log.warn("[ToolEmbedding] delete 失败: toolId={}, err={}", toolId, result.getException().toString());
            }
        } catch (Exception ex) {
            log.warn("[ToolEmbedding] delete 异常: toolId={}, err={}", toolId, ex.toString());
        }
    }

    // ==================== 全量重建 ====================

    /**
     * 全量重建：拉所有 tool_definition → 逐条 upsert。
     * 调用方（Controller）应以异步方式触发。
     */
    public RebuildResult rebuildAll() {
        int success = 0;
        int skipped = 0;
        int failed = 0;
        if (!properties.isEnabled() || !collectionReady.get()) {
            return new RebuildResult(0, 0, 0, "Tool Retrieval 未启用或 collection 未就绪");
        }
        List<ToolDefinitionEntity> all = toolDefinitionMapper.selectList(new LambdaQueryWrapper<>());
        for (ToolDefinitionEntity tool : all) {
            try {
                String text = buildText(tool);
                if (text == null || text.isBlank()) {
                    delete(tool.getId());
                    skipped++;
                    continue;
                }
                List<Float> vector = embeddingClient.embed(text);
                delete(tool.getId());
                insert(tool, text, vector);
                success++;
            } catch (Exception ex) {
                log.warn("[ToolEmbedding] rebuild 条目失败: id={}, name={}, err={}",
                        tool.getId(), tool.getName(), ex.toString());
                failed++;
            }
        }
        log.info("[ToolEmbedding] rebuildAll 完成: success={}, skipped={}, failed={}", success, skipped, failed);
        return new RebuildResult(success, skipped, failed, null);
    }

    // ==================== 内部辅助 ====================

    private void insert(ToolDefinitionEntity tool, String text, List<Float> vector) {
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field(F_ID, List.of(tool.getId())));
        fields.add(new InsertParam.Field(F_PROJECT, List.of(nvl(tool.getProjectId()))));
        fields.add(new InsertParam.Field(F_MODULE, List.of(nvl(tool.getModuleId()))));
        fields.add(new InsertParam.Field(F_ENABLED, List.of(Boolean.TRUE.equals(tool.getEnabled()))));
        fields.add(new InsertParam.Field(F_VISIBLE, List.of(Boolean.TRUE.equals(tool.getAgentVisible()))));
        fields.add(new InsertParam.Field(F_KIND, List.of(normalizeKind(tool.getKind()))));
        fields.add(new InsertParam.Field(F_TEXT, List.of(truncate(text, TEXT_MAX_CHARS))));
        fields.add(new InsertParam.Field(F_VECTOR, List.of(vector)));

        R<MutationResult> result = milvus.insert(InsertParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withFields(fields)
                .build());
        if (result.getException() != null) {
            throw new RuntimeException("Milvus insert 失败: " + result.getException().getMessage(),
                    result.getException());
        }
    }

    /**
     * 决定入库向量所用文本：合并 AI 理解、描述与工具名（均为非空才拼接），供语义召回。
     * <p>
     * 原先仅有 ai_description 时不再独占一行逻辑，否则「描述」字段不会进入 Milvus，
     * 与用户在前端看到的 Tool 描述不一致。
     */
    public static String buildText(ToolDefinitionEntity tool) {
        if (tool == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (hasText(tool.getAiDescription())) {
            sb.append(tool.getAiDescription().trim());
        }
        if (hasText(tool.getDescription())) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(tool.getDescription().trim());
        }
        if (hasText(tool.getName())) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(tool.getName().trim());
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static long nvl(Long v) {
        return v == null ? 0L : v;
    }

    private static String normalizeKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return "TOOL";
        }
        return raw.trim().toUpperCase();
    }

    /**
     * 通过 describeCollection 探测是否含 {@link #F_KIND} 字段，判定 schema 是否需要升级。
     */
    private boolean hasKindField(String collectionName) {
        try {
            R<DescribeCollectionResponse> resp = milvus.describeCollection(
                    DescribeCollectionParam.newBuilder().withCollectionName(collectionName).build());
            if (resp == null || resp.getData() == null || resp.getData().getSchema() == null) {
                return false;
            }
            return resp.getData().getSchema().getFieldsList().stream()
                    .anyMatch(f -> F_KIND.equals(f.getName()));
        } catch (Exception ex) {
            log.debug("[ToolEmbedding] describeCollection 失败，视作缺字段重建: {}", ex.toString());
            return false;
        }
    }

    public record RebuildResult(int success, int skipped, int failed, String message) {
    }
}
