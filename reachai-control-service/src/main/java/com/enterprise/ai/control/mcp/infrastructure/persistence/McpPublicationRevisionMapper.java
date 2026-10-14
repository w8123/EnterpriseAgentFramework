package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface McpPublicationRevisionMapper extends BaseMapper<McpPublicationRevisionEntity> {
    @org.apache.ibatis.annotations.Select("<script>SELECT id, publication_id, revision_no, published_at, "
            + "SHA2(tools_snapshot_json, 256) AS snapshot_fingerprint FROM control_mcp_publication_revision "
            + "WHERE publication_id IN <foreach collection='publicationIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "ORDER BY id LIMIT #{limit}</script>")
    java.util.List<ReferenceMetadata> referenceMetadata(
            @org.apache.ibatis.annotations.Param("publicationIds") java.util.Collection<Long> publicationIds,
            @org.apache.ibatis.annotations.Param("limit") int limit);

    /** Cache misses need the frozen tools body and identity, never the separate risk-summary JSON. */
    @org.apache.ibatis.annotations.Select("<script>SELECT id, publication_id, revision_no, published_at, tools_snapshot_json "
            + "FROM control_mcp_publication_revision WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    java.util.List<McpPublicationRevisionEntity> referenceBodies(
            @org.apache.ibatis.annotations.Param("ids") java.util.Collection<Long> ids);

    @lombok.Data
    class ReferenceMetadata {
        private Long id;
        private Long publicationId;
        private Integer revisionNo;
        private java.time.LocalDateTime publishedAt;
        private String snapshotFingerprint;
    }
}
