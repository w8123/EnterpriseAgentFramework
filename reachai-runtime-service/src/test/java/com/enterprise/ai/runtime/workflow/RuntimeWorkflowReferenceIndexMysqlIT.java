package com.enterprise.ai.runtime.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 使用实际 Mapper SQL 检查索引计划；不以小型夹具推断生产吞吐量。 */
@EnabledIfSystemProperty(named = "reachai.mysql.workflowReferenceVerification", matches = "true")
class RuntimeWorkflowReferenceIndexMysqlIT extends RuntimeWorkflowReferenceIndexPersistenceTest {
    @Override protected boolean useMysql() { return true; }

    @Test void reverseLookupUsesReferenceIndexAndCoverageUsesHeaderIndex() throws Exception {
        var workflowsToInsert = new ArrayList<Object[]>();
        var rowsToInsert = new ArrayList<Object[]>();
        for (int i = 0; i < 512; i++) {
            String id = "plan-" + i;
            workflowsToInsert.add(new Object[]{id, id, "计划验证", "{\"nodes\":[]}"});
            rowsToInsert.add(new Object[]{id, -1, null, "READY", "[]"});
            rowsToInsert.add(new Object[]{id, 0, i == 17 ? "orders_read" : "unrelated_" + i, "READY", "[]"});
        }
        tx.executeWithoutResult(status -> {
            jdbc.batchUpdate("INSERT INTO runtime_workflow(id,key_slug,name,graph_spec_json,updated_at) VALUES(?,?,?,?,NULL)", workflowsToInsert);
            jdbc.batchUpdate("INSERT INTO runtime_workflow_capability_reference(workflow_id,workflow_version_id,node_ordinal,reference_key,state,warnings_json) VALUES(?,0,?,?,?,?)", rowsToInsert);
        });
        jdbc.execute("ANALYZE TABLE runtime_workflow_capability_reference");
        var evidence = index.inspect(List.of("orders_read"));
        assertEquals(1, evidence.hits().size());
        assertEquals("plan-17", evidence.hits().get(0).workflowId());
        assertTrue(evidence.warnings().isEmpty());
        assertTrue(index.inspect(List.of("ORDERS_READ")).hits().isEmpty(), "引用标识沿用二进制大小写语义");
        var usages = explain("findUsages", Map.of("keys", List.of("orders_read"), "limit", 10001));
        assertTrue(usages.stream().anyMatch(row -> "r".equals(row.get("table"))
                && "idx_workflow_reference_key".equals(row.get("key"))
                && List.of("ref", "range").contains(row.get("type"))), usages.toString());
        var warnings = explain("incompleteWarnings", Map.of("limit", 10001));
        assertTrue(warnings.stream().anyMatch(row -> "idx_workflow_reference_coverage".equals(row.get("key"))), warnings.toString());
        var plans = new LinkedHashMap<String, Object>();
        plans.put("findUsages", usages);
        plans.put("incompleteWarnings", warnings);
        plans.put("missingDraftIds", explain("missingDraftIds", Map.of("limit", 1)));
        plans.put("missingVersionIds", explain("missingVersionIds", Map.of("limit", 1)));
        System.out.println("WORKFLOW_REFERENCE_MYSQL_PLANS " + new ObjectMapper().writeValueAsString(plans));
    }

    private List<Map<String, Object>> explain(String method, Map<String, Object> parameters) throws Exception {
        var mapped = session.getConfiguration().getMappedStatement(RuntimeWorkflowReferenceMapper.class.getName() + "." + method);
        var bound = mapped.getBoundSql(parameters);
        var result = new ArrayList<Map<String, Object>>();
        try (var connection = jdbc.getDataSource().getConnection();
             var statement = connection.prepareStatement("EXPLAIN " + bound.getSql())) {
            new DefaultParameterHandler(mapped, parameters, bound).setParameters(statement);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var item = new LinkedHashMap<String, Object>();
                    for (String key : List.of("table", "type", "possible_keys", "key", "rows", "Extra")) item.put(key, rows.getObject(key));
                    result.add(item);
                }
            }
        }
        return result;
    }
}
