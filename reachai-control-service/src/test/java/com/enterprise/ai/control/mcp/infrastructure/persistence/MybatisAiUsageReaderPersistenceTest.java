package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.control.mcp.application.usage.AiUsageReader;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runs the real grouped SQL behind 「AI 使用情况」 against H2 in MySQL mode. */
class MybatisAiUsageReaderPersistenceTest {

    private SqlSession session;
    private JdbcTemplate jdbc;
    private MybatisAiUsageReader reader;
    private final LocalDateTime now = LocalDateTime.now();

    @BeforeEach
    void prepareDatabase() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:ai_usage_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE control_mcp_call_log (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, direction VARCHAR(16), method VARCHAR(64),
                  tool_name VARCHAR(128), project_code VARCHAR(96), success BOOLEAN, created_at TIMESTAMP,
                  connection_id BIGINT, external_user_id VARCHAR(128), tool_client_name VARCHAR(64))
                """);
        jdbc.execute("""
                CREATE TABLE control_mcp_write_approval (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, connection_id BIGINT, business_user_id BIGINT,
                  external_user_id VARCHAR(128), user_name VARCHAR(128), tool_kind VARCHAR(32),
                  project_code VARCHAR(96), system_name VARCHAR(128), tool_name VARCHAR(128),
                  tool_description VARCHAR(1000), state VARCHAR(16), created_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE control_ai_tool_connection (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(96), business_user_id BIGINT,
                  app_id VARCHAR(96), external_user_id VARCHAR(128), user_name VARCHAR(128), tool_kind VARCHAR(32),
                  key_prefix VARCHAR(16), key_hash VARCHAR(128), state VARCHAR(16), expires_at TIMESTAMP,
                  last_used_at TIMESTAMP, revoked_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE control_ai_tool_connection_system (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, connection_id BIGINT, project_code VARCHAR(96),
                  publication_id BIGINT, created_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE control_agent_skill (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, standard_name VARCHAR(64), display_name VARCHAR(128),
                  project_code VARCHAR(96))
                """);
        jdbc.execute("""
                CREATE TABLE control_agent_skill_version (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, skill_id BIGINT, version VARCHAR(32), status VARCHAR(32),
                  source_type VARCHAR(32), created_by VARCHAR(128), created_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE control_external_user_binding (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, business_user_id BIGINT, app_id VARCHAR(96),
                  external_user_id VARCHAR(128), external_user_name VARCHAR(128))
                """);
        jdbc.execute("CREATE TABLE control_business_user (id BIGINT PRIMARY KEY, display_name VARCHAR(128))");

        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AiUsageMapper.class);
        configuration.addMapper(AiToolConnectionMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        session = factory.getObject().openSession(true);
        reader = new MybatisAiUsageReader(session.getMapper(AiUsageMapper.class),
                session.getMapper(AiToolConnectionMapper.class));
    }

    @AfterEach
    void closeSession() {
        if (session != null) session.close();
    }

    @Test
    void groupsEmployeeToolCallsAndSkipsListingAndApprovalStatusPolls() {
        call(7, "yudao-erp", "tools/call", "yudao_erp_query_stock", true, now.minusHours(1));
        call(7, "yudao-erp", "tools/call", "yudao_erp_query_stock", false, now.minusHours(2));
        call(7, "yudao-erp", "tools/call", "yudao_erp_create_customer", true, now.minusHours(3));
        call(7, "yudao-erp", "tools/list", null, true, now.minusHours(3));
        call(7, "yudao-erp", "tools/call", "reachai_approval_status", true, now.minusHours(3));
        call(7, "yudao-erp", "tools/call", "yudao_erp_query_stock", true, now.minusDays(40));
        call(8, "billing", "tools/call", "billing_list_invoices", true, now.minusHours(1));
        call(null, "yudao-erp", "tools/call", "yudao_erp_query_stock", true, now.minusHours(1));

        List<AiUsageReader.CallRow> rows = reader.employeeCalls(now.minusDays(30), null).stream()
                .sorted(Comparator.comparing(AiUsageReader.CallRow::toolName)).toList();
        assertEquals(List.of("billing_list_invoices", "yudao_erp_create_customer", "yudao_erp_query_stock"),
                rows.stream().map(AiUsageReader.CallRow::toolName).toList());
        AiUsageReader.CallRow stock = rows.get(2);
        assertEquals(7L, stock.connectionId());
        assertEquals(2, stock.calls());
        assertEquals(1, stock.failed());

        assertEquals(2, reader.employeeCalls(now.minusDays(30), "yudao-erp").size());
    }

    @Test
    void groupsApprovalsByStateWithTheConnectionsBusinessSystem() {
        jdbc.update("INSERT INTO control_ai_tool_connection (id, business_user_id, app_id, external_user_id, user_name, tool_kind, state) VALUES (7, 11, 'yudao-erp', '101', '张三', 'CLAUDE_CODE', 'ACTIVE')");
        approval(7, "SUCCEEDED", now.minusHours(1));
        approval(7, "SUCCEEDED", now.minusHours(2));
        approval(7, "REJECTED", now.minusHours(2));
        approval(7, "PENDING", now.minusDays(40));

        List<AiUsageReader.ApprovalRow> rows = reader.approvals(now.minusDays(30), "yudao-erp").stream()
                .sorted(Comparator.comparing(AiUsageReader.ApprovalRow::state)).toList();
        assertEquals(List.of("REJECTED", "SUCCEEDED"), rows.stream().map(AiUsageReader.ApprovalRow::state).toList());
        assertEquals(2, rows.get(1).count());
        assertEquals("yudao-erp", rows.get(1).appId());
        assertEquals("新建客户", rows.get(1).toolDescription());
        assertEquals(11L, rows.get(1).businessUserId());
    }

    @Test
    void countsActiveConnectionsPerSystemAndReadsTheirOwners() {
        jdbc.update("INSERT INTO control_ai_tool_connection (id, business_user_id, app_id, external_user_id, user_name, tool_kind, state, expires_at) VALUES (1, 11, 'yudao-erp', '101', '张三', 'CODEX', 'ACTIVE', ?)", now.plusDays(10));
        jdbc.update("INSERT INTO control_ai_tool_connection (id, business_user_id, app_id, external_user_id, user_name, tool_kind, state, expires_at) VALUES (2, 12, 'yudao-erp', '102', '李四', 'CLAUDE_CODE', 'ACTIVE', ?)", now.minusDays(1));
        jdbc.update("INSERT INTO control_ai_tool_connection (id, business_user_id, app_id, external_user_id, user_name, tool_kind, state) VALUES (3, 13, 'billing', '201', '王五', 'OTHER', 'REVOKED')");
        jdbc.update("INSERT INTO control_ai_tool_connection (id, business_user_id, app_id, external_user_id, user_name, tool_kind, state) VALUES (4, 14, 'billing', '202', '赵六', 'OTHER', 'ACTIVE')");
        jdbc.update("INSERT INTO control_ai_tool_connection_system (connection_id, project_code) VALUES (1, 'yudao-erp'), (2, 'yudao-erp'), (4, 'billing'), (4, 'yudao-erp')");

        assertEquals(2, reader.activeConnections(null));
        assertEquals(2, reader.activeConnections("yudao-erp"));
        assertEquals(1, reader.activeConnections("billing"));
        List<AiUsageReader.ConnectionRow> owners = reader.connections(Set.of(1L, 3L)).stream()
                .sorted(Comparator.comparing(AiUsageReader.ConnectionRow::id)).toList();
        assertEquals(List.of("张三", "王五"), owners.stream().map(AiUsageReader.ConnectionRow::userName).toList());
        assertEquals("CODEX", owners.get(0).toolKind());
    }

    @Test
    void listsOnlySkillsCapturedFromRunRecords() {
        jdbc.update("INSERT INTO control_agent_skill (id, standard_name, display_name, project_code) VALUES (4, 'yudao-erp-erp-customer-create', '在芋道 ERP 中新建客户', 'yudao-erp'), (5, 'uploaded', '上传的 Skill', 'yudao-erp')");
        jdbc.update("INSERT INTO control_agent_skill_version (skill_id, version, status, source_type, created_by, created_at) VALUES (4, '1.0.0', 'PUBLISHED', 'RUN_CAPTURE', 'admin', ?)", now.minusDays(1));
        jdbc.update("INSERT INTO control_agent_skill_version (skill_id, version, status, source_type, created_by, created_at) VALUES (5, '1.0.0', 'PUBLISHED', 'UPLOAD', 'admin', ?)", now.minusDays(1));

        assertEquals(1, reader.capturedSkillCount(now.minusDays(30), null));
        assertEquals(0, reader.capturedSkillCount(now.minusDays(30), "billing"));
        List<AiUsageReader.SkillRow> skills = reader.capturedSkills(now.minusDays(30), "yudao-erp", 10);
        assertEquals(1, skills.size());
        assertEquals("在芋道 ERP 中新建客户", skills.get(0).displayName());
    }

    @Test
    void resolvesExactAccountPairsAndBusinessUserNames() {
        jdbc.update("INSERT INTO control_external_user_binding (business_user_id, app_id, external_user_id, external_user_name) VALUES (11, 'yudao-erp', '101', '张三'), (12, 'billing', '102', '李四'), (13, 'yudao-erp', '102', '王五')");
        jdbc.update("INSERT INTO control_business_user (id, display_name) VALUES (11, '张三（销售）'), (12, NULL)");

        List<AiUsageReader.AccountRow> accounts = reader.accounts(List.of(
                new AiUsageReader.AccountKey("yudao-erp", "101"), new AiUsageReader.AccountKey("billing", "102")));
        assertEquals(Set.of(11L, 12L), Set.copyOf(accounts.stream().map(AiUsageReader.AccountRow::businessUserId).toList()));
        assertEquals("张三（销售）", reader.businessUserNames(Set.of(11L, 12L)).get(11L));
        assertEquals(1, reader.businessUserNames(Set.of(11L, 12L)).size());
    }

    private void call(Integer connectionId, String projectCode, String method, String toolName, boolean success,
                      LocalDateTime at) {
        jdbc.update("INSERT INTO control_mcp_call_log (direction, method, tool_name, project_code, success, created_at, connection_id) VALUES ('OUTBOUND', ?, ?, ?, ?, ?, ?)",
                method, toolName, projectCode, success, at, connectionId);
    }

    private void approval(long connectionId, String state, LocalDateTime at) {
        jdbc.update("INSERT INTO control_mcp_write_approval (connection_id, business_user_id, external_user_id, user_name, tool_kind, project_code, system_name, tool_name, tool_description, state, created_at) VALUES (?, 11, '101', '张三', 'CLAUDE_CODE', 'yudao-erp', '芋道 ERP', 'yudao_erp_create_customer', '新建客户', ?, ?)",
                connectionId, state, at);
    }
}
