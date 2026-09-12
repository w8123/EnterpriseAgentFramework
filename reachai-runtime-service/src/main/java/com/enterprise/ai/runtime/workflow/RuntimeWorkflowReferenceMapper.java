package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.Collection;
import java.util.List;

@Mapper
public interface RuntimeWorkflowReferenceMapper extends BaseMapper<RuntimeWorkflowReferenceEntity> {
    @Insert("""
            <script>
            INSERT INTO runtime_workflow_capability_reference
              (workflow_id, workflow_version_id, node_ordinal, node_id, reference_key, state, warnings_json, indexed_revision)
            VALUES <foreach collection="rows" item="row" separator=",">
              (#{row.workflowId}, #{row.workflowVersionId}, #{row.nodeOrdinal}, #{row.nodeId},
               #{row.referenceKey}, #{row.state}, #{row.warningsJson}, #{row.indexedRevision})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<RuntimeWorkflowReferenceEntity> rows);

    @Select("""
            <script>
            SELECT r.workflow_id, COALESCE(w.name,r.workflow_id) workflow_name,
                   NULLIF(r.workflow_version_id,0) version_id, v.version, v.status,
                   r.node_id, r.reference_key
            FROM runtime_workflow_capability_reference r
            LEFT JOIN runtime_workflow w ON w.id=r.workflow_id
            LEFT JOIN runtime_workflow_version v ON v.id=r.workflow_version_id AND v.workflow_id=r.workflow_id
            WHERE r.node_ordinal &gt;= 0 AND r.reference_key IN
              <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
              AND ((r.workflow_version_id=0 AND w.id IS NOT NULL) OR v.id IS NOT NULL)
            ORDER BY r.workflow_id,r.workflow_version_id,r.node_ordinal
            LIMIT #{limit}
            </script>
            """)
    List<UsageRow> findUsages(@Param("keys") Collection<String> keys, @Param("limit") int limit);

    @Select("""
            SELECT w.id FROM runtime_workflow w
            LEFT JOIN runtime_workflow_capability_reference h
              ON h.workflow_id=w.id AND h.workflow_version_id=0 AND h.node_ordinal=-1
            WHERE h.id IS NULL OR h.indexed_revision != w.updated_at
               OR (h.indexed_revision IS NULL AND w.updated_at IS NOT NULL)
               OR (h.indexed_revision IS NOT NULL AND w.updated_at IS NULL)
            ORDER BY w.id LIMIT #{limit}
            """)
    List<String> missingDraftIds(@Param("limit") int limit);

    @Select("""
            SELECT v.id FROM runtime_workflow_version v
            LEFT JOIN runtime_workflow_capability_reference h
              ON h.workflow_id=v.workflow_id AND h.workflow_version_id=v.id AND h.node_ordinal=-1
            WHERE h.id IS NULL ORDER BY v.id LIMIT #{limit}
            """)
    List<Long> missingVersionIds(@Param("limit") int limit);

    @Select("""
            SELECT warnings_json FROM runtime_workflow_capability_reference
            WHERE node_ordinal=-1 AND state='PARTIAL' ORDER BY id LIMIT #{limit}
            """)
    List<String> incompleteWarnings(@Param("limit") int limit);

    @Data
    class UsageRow {
        private String workflowId;
        private String workflowName;
        private Long versionId;
        private String version;
        private String status;
        private String nodeId;
        private String referenceKey;
    }
}
