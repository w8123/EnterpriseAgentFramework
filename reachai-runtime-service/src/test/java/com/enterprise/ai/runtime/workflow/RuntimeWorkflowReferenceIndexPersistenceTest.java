package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeWorkflowReferenceIndexPersistenceTest {
    private com.enterprise.ai.common.testing.ClonedDevelopmentMysqlDatabase mysql;
    SqlSessionTemplate session;
    protected boolean useMysql() { return false; }
    RuntimeWorkflowDefinitionMapper workflows;
    RuntimeWorkflowVersionMapper versions;
    RuntimeWorkflowReferenceMapper references;
    RuntimeWorkflowReferenceIndex index;
    TransactionTemplate tx;
    JdbcTemplate jdbc;
    final String graph = "{\"nodes\":[{\"id\":\"读取订单\",\"type\":\"TOOL\",\"config\":{\"toolName\":\"orders_read\"}}]}";

    @BeforeEach void database() throws Exception {
        javax.sql.DataSource source;
        if (useMysql()) {
            mysql = new com.enterprise.ai.common.testing.ClonedDevelopmentMysqlDatabase(
                    "reachai.mysql.workflowReferenceVerification", "audit_reference", "runtime_",
                    List.of("runtime_workflow", "runtime_workflow_version", "runtime_workflow_capability_reference"));
            source = mysql;
            try (var connection = source.getConnection()) {
                assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
            }
        } else {
            source = new DriverManagerDataSource("jdbc:h2:mem:reference_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        }
        jdbc = new JdbcTemplate(source);
        if (!useMysql()) {
            String baseline = Files.readString(Path.of("../sql/initV2.sql"));
            for (String table : List.of("runtime_workflow", "runtime_workflow_version", "runtime_workflow_capability_reference")) {
                var match = Pattern.compile("(?is)CREATE TABLE IF NOT EXISTS `" + table + "`\\s*\\(.*?;").matcher(baseline);
                assertTrue(match.find(), table);
                jdbc.execute(match.group().replaceAll("(?is)\\) ENGINE=.*?;", ");")
                        .replaceAll("(?i)COLLATE \\w+", "")
                        .replaceAll("`reference_key`\\(191\\)", "`reference_key`"));
            }
        }
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        for (Class<?> type : List.of(RuntimeWorkflowDefinitionMapper.class, RuntimeWorkflowVersionMapper.class,
                RuntimeWorkflowReferenceMapper.class)) config.addMapper(type);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        session = new SqlSessionTemplate(factory.getObject());
        workflows = session.getMapper(RuntimeWorkflowDefinitionMapper.class);
        versions = session.getMapper(RuntimeWorkflowVersionMapper.class);
        references = session.getMapper(RuntimeWorkflowReferenceMapper.class);
        index = new RuntimeWorkflowReferenceIndex(references, workflows, versions, new ObjectMapper());
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterEach void cleanup() {
        if (mysql != null) mysql.close();
        else if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    @Test void indexedLookupFindsCrossProjectDraftAndPinnedHistoricalVersion() {
        var workflow = workflow(graph);
        var version = new RuntimeWorkflowVersionEntity(); version.setWorkflowId(workflow.getId()); version.setVersion("v1");
        version.setStatus("RETIRED"); version.setSnapshotJson("{}"); version.setGraphSpecSnapshotJson(graph);
        tx.execute(status -> { workflows.insert(workflow); versions.insert(version); index.indexDraft(workflow); index.indexVersion(version); return null; });
        assertEquals(java.util.Map.of(version.getId(), workflow.getId()),
                index.versionOwners(List.of(version.getId(), Long.MAX_VALUE)));
        var evidence = index.inspect(Set.of("orders_read"));
        assertTrue(evidence.warnings().isEmpty()); assertEquals(2, evidence.hits().size());
        assertTrue(evidence.hits().stream().anyMatch(hit -> "RETIRED".equals(hit.status())));
        assertTrue(evidence.hits().stream().allMatch(hit -> "读取订单".equals(hit.nodeId())));
        assertTrue(index.inspect(Set.of("unrelated")).hits().isEmpty());
        version.setStatus("ACTIVE"); versions.updateById(version);
        assertTrue(index.inspect(Set.of("orders_read")).hits().stream().anyMatch(hit -> "ACTIVE".equals(hit.status())));
    }

    @Test void missingMalformedDynamicAndEmptyGraphsRemainDistinguishable() {
        var workflow = workflow("not-json"); workflows.insert(workflow);
        assertTrue(index.inspect(Set.of("orders_read")).warnings().contains("REFERENCE_INDEX_INCOMPLETE"));
        tx.execute(status -> { index.indexDraft(workflow); return null; });
        assertTrue(index.inspect(Set.of("orders_read")).warnings().contains("GRAPH_UNREADABLE"));
        workflow.setGraphSpecJson(graph.replace("orders_read", "${runtimeTool}"));
        tx.execute(status -> { workflows.updateById(workflow); index.indexDraft(workflow); return null; });
        assertTrue(index.inspect(Set.of("orders_read")).warnings().contains("UNRESOLVED_TOOL_REFERENCE"));
        workflow.setGraphSpecJson("{\"nodes\":[]}");
        tx.execute(status -> { workflows.updateById(workflow); index.indexDraft(workflow); return null; });
        var empty = index.inspect(Set.of("orders_read"));
        assertTrue(empty.warnings().isEmpty()); assertTrue(empty.hits().isEmpty());
        assertEquals(1L, references.selectCount(null));
    }

    @Test void replacementRollbackAndBootstrapPreserveCoverage() {
        var workflow = workflow(graph);
        tx.execute(status -> { workflows.insert(workflow); index.indexDraft(workflow); return null; });
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            workflow.setGraphSpecJson(graph.replace("orders_read", "orders_new"));
            workflows.updateById(workflow); index.indexDraft(workflow); throw new IllegalStateException("later write failed");
        }));
        assertEquals(1, index.inspect(Set.of("orders_read")).hits().size());
        assertTrue(index.inspect(Set.of("orders_new")).hits().isEmpty());
        tx.execute(status -> { workflows.updateById(workflow); index.indexDraft(workflow); return null; });
        assertTrue(index.inspect(Set.of("orders_read")).hits().isEmpty());
        assertEquals(1, index.inspect(Set.of("orders_new")).hits().size());
        workflow.setUpdatedAt(workflow.getUpdatedAt().plusSeconds(1)); workflows.updateById(workflow);
        assertTrue(index.inspect(Set.of("orders_new")).warnings().contains("REFERENCE_INDEX_INCOMPLETE"));
        tx.execute(status -> { new RuntimeWorkflowReferenceBootstrap(references, index).rebuildMissing(); return null; });
        assertTrue(index.inspect(Set.of("orders_new")).warnings().isEmpty());
        tx.execute(status -> { index.delete(workflow.getId()); workflows.deleteById(workflow.getId()); return null; });
        assertTrue(index.inspect(Set.of("orders_new")).warnings().isEmpty());
        assertTrue(index.inspect(Set.of("orders_new")).hits().isEmpty());
    }

    private RuntimeWorkflowDefinitionEntity workflow(String graphJson) {
        var value = new RuntimeWorkflowDefinitionEntity(); value.setId("w-other-project"); value.setKeySlug("crm");
        value.setName("跨项目订单查询"); value.setProjectCode("crm"); value.setStatus("DRAFT"); value.setGraphSpecJson(graphJson);
        value.setUpdatedAt(LocalDateTime.of(2026, 9, 5, 10, 0)); return value;
    }
}
