package com.enterprise.ai.bizindex.vector;

import com.enterprise.ai.vector.VectorSearchRequest;
import com.enterprise.ai.vector.VectorSearchResult;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.*;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 业务索引专用的 Milvus 向量服务 —— 与知识库的 VectorService 隔离。
 * <p>
 * Collection Schema 设计为业务索引场景定制：
 * <ul>
 *   <li>biz_id — 业务主键，用于搜索后回查</li>
 *   <li>record_type — FIELD（业务字段）/ ATTACHMENT（附件 Chunk）</li>
 *   <li>owner_user_id / owner_org_id — 权限过滤字段</li>
 *   <li>biz_type — 业务子类型过滤</li>
 *   <li>file_name — 附件来源文件名</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizVectorService {

    private final MilvusServiceClient milvusClient;

    /**
     * 创建业务索引 Collection（如不存在），Schema 包含业务索引专用字段
     */
    public void ensureCollection(String collectionName, int dimension) {
        R<Boolean> hasCollection = milvusClient.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collectionName).build());
        if (hasCollection.getData() != null && hasCollection.getData()) {
            log.debug("Business index collection {} already exists", collectionName);
            return;
        }

        FieldType idField = FieldType.newBuilder()
                .withName("id").withDataType(DataType.VarChar)
                .withMaxLength(128).withPrimaryKey(true).withAutoID(false)
                .build();

        FieldType bizIdField = FieldType.newBuilder()
                .withName("biz_id").withDataType(DataType.VarChar).withMaxLength(128)
                .build();

        FieldType recordTypeField = FieldType.newBuilder()
                .withName("record_type").withDataType(DataType.VarChar).withMaxLength(16)
                .build();

        FieldType contentField = FieldType.newBuilder()
                .withName("content").withDataType(DataType.VarChar).withMaxLength(8192)
                .build();

        FieldType ownerUserIdField = FieldType.newBuilder()
                .withName("owner_user_id").withDataType(DataType.VarChar).withMaxLength(64)
                .build();

        FieldType ownerOrgIdField = FieldType.newBuilder()
                .withName("owner_org_id").withDataType(DataType.VarChar).withMaxLength(64)
                .build();

        FieldType bizTypeField = FieldType.newBuilder()
                .withName("biz_type").withDataType(DataType.VarChar).withMaxLength(64)
                .build();

        FieldType fileNameField = FieldType.newBuilder()
                .withName("file_name").withDataType(DataType.VarChar).withMaxLength(256)
                .build();

        FieldType vectorField = FieldType.newBuilder()
                .withName("vector").withDataType(DataType.FloatVector).withDimension(dimension)
                .build();

        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .addFieldType(idField)
                .addFieldType(bizIdField)
                .addFieldType(recordTypeField)
                .addFieldType(contentField)
                .addFieldType(ownerUserIdField)
                .addFieldType(ownerOrgIdField)
                .addFieldType(bizTypeField)
                .addFieldType(fileNameField)
                .addFieldType(vectorField)
                .build();

        R<RpcStatus> createResult = milvusClient.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withSchema(schema)
                .build());

        if (createResult.getException() != null) {
            throw new RuntimeException("创建业务索引 Collection 失败: " + collectionName, createResult.getException());
        }

        milvusClient.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("vector")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":1024}")
                .build());

        milvusClient.loadCollection(
                LoadCollectionParam.newBuilder().withCollectionName(collectionName).build());

        log.info("Business index collection {} created and loaded", collectionName);
    }

    /**
     * 批量插入业务索引向量
     */
    public void insert(String collectionName,
                       List<String> ids,
                       List<String> bizIds,
                       List<String> recordTypes,
                       List<String> contents,
                       List<String> ownerUserIds,
                       List<String> ownerOrgIds,
                       List<String> bizTypes,
                       List<String> fileNames,
                       List<List<Float>> vectors) {

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", ids));
        fields.add(new InsertParam.Field("biz_id", bizIds));
        fields.add(new InsertParam.Field("record_type", recordTypes));
        fields.add(new InsertParam.Field("content", contents));
        fields.add(new InsertParam.Field("owner_user_id", ownerUserIds));
        fields.add(new InsertParam.Field("owner_org_id", ownerOrgIds));
        fields.add(new InsertParam.Field("biz_type", bizTypes));
        fields.add(new InsertParam.Field("file_name", fileNames));
        fields.add(new InsertParam.Field("vector", vectors));

        R<MutationResult> result = milvusClient.insert(InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build());

        if (result.getException() != null) {
            throw new RuntimeException("业务索引向量插入失败", result.getException());
        }
        log.info("Inserted {} vectors into business index {}", ids.size(), collectionName);
    }

    /**
     * 语义搜索
     */
    public List<VectorSearchResult> search(VectorSearchRequest request) {
        List<String> outputFields = request.getOutputFields() != null
                ? request.getOutputFields()
                : List.of("id", "biz_id", "record_type", "content", "owner_user_id",
                          "owner_org_id", "biz_type", "file_name");

        SearchParam.Builder builder = SearchParam.newBuilder()
                .withCollectionName(request.getCollectionName())
                .withMetricType(MetricType.COSINE)
                .withTopK(request.getTopK())
                .withVectors(List.of(request.getQueryVector()))
                .withVectorFieldName("vector")
                .withOutFields(outputFields)
                .withParams("{\"nprobe\":16}");

        if (request.getFilterExpression() != null && !request.getFilterExpression().isEmpty()) {
            builder.withExpr(request.getFilterExpression());
        }

        R<SearchResults> response = milvusClient.search(builder.build());
        if (response.getException() != null) {
            throw new RuntimeException("业务索引向量搜索失败", response.getException());
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        List<VectorSearchResult> results = new ArrayList<>();

        if (wrapper.getRowRecords(0) == null) {
            return results;
        }

        for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
            SearchResultsWrapper.IDScore idScore = wrapper.getIDScore(0).get(i);
            Map<String, Object> fieldMap = new HashMap<>();
            var row = wrapper.getRowRecords(0).get(i);
            for (String fieldName : row.getFieldValues().keySet()) {
                fieldMap.put(fieldName, row.get(fieldName));
            }
            results.add(VectorSearchResult.builder()
                    .id(String.valueOf(idScore.getStrID()))
                    .score(idScore.getScore())
                    .fields(fieldMap)
                    .build());
        }
        return results;
    }

    /**
     * 按 biz_id 删除向量（删除某条业务记录的全部向量，含附件 Chunk）
     */
    public void deleteByBizId(String collectionName, String bizId) {
        String expr = "biz_id == \"" + bizId + "\"";
        R<MutationResult> result = milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build());
        if (result.getException() != null) {
            throw new RuntimeException("业务索引向量删除失败", result.getException());
        }
        log.info("Deleted vectors with biz_id={} from {}", bizId, collectionName);
    }

    /**
     * 删除整个 Collection
     */
    public void dropCollection(String collectionName) {
        milvusClient.dropCollection(
                DropCollectionParam.newBuilder().withCollectionName(collectionName).build());
        log.info("Dropped business index collection {}", collectionName);
    }
}
