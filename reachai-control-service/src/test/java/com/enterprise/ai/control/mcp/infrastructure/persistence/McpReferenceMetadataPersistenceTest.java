package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Exercises the actual mapper projection; the H2 SHA2 alias models MySQL's UTF-8 digest. */
public class McpReferenceMetadataPersistenceTest {
    public static String sha2(String value, Integer bits) {
        if (!Integer.valueOf(256).equals(bits)) throw new IllegalArgumentException("Unsupported digest");
        return McpPublishedReferenceReader.fingerprint(value);
    }

    @Test void metadataUsesFreshUtf8FingerprintAndParsedCacheNeverHidesRepairOrUnpublish() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:mcp_reference_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        try {
            jdbc.execute("CREATE ALIAS SHA2 FOR '" + getClass().getName() + ".sha2'");
            jdbc.execute("CREATE TABLE control_mcp_publication(id BIGINT PRIMARY KEY,name VARCHAR(128),state VARCHAR(32),current_revision_id BIGINT)");
            jdbc.execute("CREATE TABLE control_mcp_publication_revision(id BIGINT PRIMARY KEY,publication_id BIGINT,revision_no INT,tools_snapshot_json CLOB,risk_summary_json CLOB,published_at TIMESTAMP)");
            jdbc.update("INSERT INTO control_mcp_publication VALUES (1,'发布','PUBLISHED',12)");
            String snapshot = "[{\"sourceKind\":\"CAPABILITY\",\"sourceRef\":\"orders:读取\"}]";
            jdbc.update("INSERT INTO control_mcp_publication_revision VALUES (12,1,1,?,?,'2026-10-13 12:00:00')", snapshot, "x".repeat(100_000));
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            config.addMapper(McpPublicationMapper.class); config.addMapper(McpPublicationRevisionMapper.class);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
            var session = new SqlSessionTemplate(factory.getObject());
            var publications = session.getMapper(McpPublicationMapper.class);
            var revisions = spy(session.getMapper(McpPublicationRevisionMapper.class));
            var metadata = revisions.referenceMetadata(List.of(1L), 1).get(0);
            assertEquals(1L, metadata.getPublicationId()); assertEquals(1, metadata.getRevisionNo());
            assertEquals(McpPublishedReferenceReader.fingerprint(snapshot), metadata.getSnapshotFingerprint());
            assertNotNull(metadata.getPublishedAt());
            var reader = new McpPublishedReferenceReader(publications, revisions, new ObjectMapper());
            assertTrue(reader.inspect().complete()); assertTrue(reader.inspect().complete());
            verify(revisions, times(1)).referenceBodies(any());
            assertNull(revisions.referenceBodies(List.of(12L)).get(0).getRiskSummaryJson());
            jdbc.update("UPDATE control_mcp_publication_revision SET tools_snapshot_json='null' WHERE id=12");
            assertFalse(reader.inspect().complete()); verify(revisions, times(3)).referenceBodies(any());
            jdbc.update("UPDATE control_mcp_publication SET state='DRAFT' WHERE id=1");
            assertEquals(List.of(), reader.inspect().bindings());
        } finally { jdbc.execute("SHUTDOWN"); }
    }
}
