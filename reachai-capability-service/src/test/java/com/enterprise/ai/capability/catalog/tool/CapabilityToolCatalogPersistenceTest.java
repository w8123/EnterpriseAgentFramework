package com.enterprise.ai.capability.catalog.tool;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodCatalogInternalController;
import com.enterprise.ai.capability.config.CapabilityMybatisPlusConfiguration;
import com.enterprise.ai.capability.internal.CapabilitySourceContractGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Exercises the owner-table business-method read model through real H2/MyBatis pagination. */
class CapabilityToolCatalogPersistenceTest {

    private JdbcTemplate jdbc;
    private ToolDefinitionMapper tools;
    private CapabilityToolCatalogService service;

    @BeforeEach
    void database() throws Exception {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:business_methods_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        createToolDefinitionTable();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addInterceptor(new CapabilityMybatisPlusConfiguration().capabilityMybatisPlusInterceptor());
        configuration.addMapper(ToolDefinitionMapper.class);
        var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        tools = session.getMapper(ToolDefinitionMapper.class);
        service = new CapabilityToolCatalogService(
                tools,
                mock(ScanProjectMapper.class),
                mock(ScanProjectToolMapper.class),
                new ObjectMapper());
    }

    @Test
    void pagesOnlyBusinessMethodsWithRealCountsFiltersProjectsAndMixedProjectionRecords() {
        insert("orders_read", "A 查询订单", 7L, "BUSINESS_METHOD", true, "正常声明");
        insert("orders_cancel", "B 取消订单", 7L, "BUSINESS_METHOD", false, "已停用声明");
        insert("orders_drifted", "C 漂移订单", 7L, "BUSINESS_METHOD", true, "存在契约漂移的声明");
        insert("other_cancel", "B 取消订单", 8L, "BUSINESS_METHOD", true, "同展示名的其他项目声明");
        insert("orders_http_api", "HTTP 订单入口", 7L, "HTTP_API", true, "技术 API 投影");
        insert("orders_legacy", "旧订单定义", 7L, "UNCLASSIFIED", true, "存量未分类投影");

        IPage<ToolDefinitionEntity> firstPage = service.pageBusinessMethods(1, 2, null, null, 7L);
        IPage<ToolDefinitionEntity> secondPage = service.pageBusinessMethods(2, 2, null, null, 7L);
        IPage<ToolDefinitionEntity> disabled = service.pageBusinessMethods(1, 20, null, false, 7L);
        IPage<ToolDefinitionEntity> drifted = service.pageBusinessMethods(1, 20, "漂移", null, 7L);
        IPage<ToolDefinitionEntity> sameTitleAcrossProjects = service.pageBusinessMethods(1, 20, "取消订单", null, null);

        assertEquals(3, firstPage.getTotal());
        assertEquals(List.of("orders_read", "orders_cancel"), firstPage.getRecords().stream().map(ToolDefinitionEntity::getName).toList());
        assertEquals(1, secondPage.getRecords().size());
        assertEquals("orders_drifted", secondPage.getRecords().get(0).getName());
        assertEquals(List.of("orders_cancel"), disabled.getRecords().stream().map(ToolDefinitionEntity::getName).toList());
        assertEquals(List.of("orders_drifted"), drifted.getRecords().stream().map(ToolDefinitionEntity::getName).toList());
        assertEquals(2, sameTitleAcrossProjects.getTotal());
        assertTrue(sameTitleAcrossProjects.getRecords().stream()
                .allMatch(record -> "BUSINESS_METHOD".equals(record.getAssetType())));
        assertFalse(firstPage.getRecords().stream().anyMatch(record -> "orders_http_api".equals(record.getName())));
        assertFalse(firstPage.getRecords().stream().anyMatch(record -> "orders_legacy".equals(record.getName())));
    }

    @Test
    void rejectsNonBusinessMethodDetailAndRecordsThatLegacyNullTypeCannotExistInTheCurrentSchema() {
        insert("orders_read", "查询订单", 7L, "BUSINESS_METHOD", true, "正常声明");
        insert("orders_http_api", "HTTP 订单入口", 7L, "HTTP_API", true, "技术 API 投影");
        BusinessMethodCatalogInternalController controller = new BusinessMethodCatalogInternalController(
                service, mock(CapabilitySourceContractGuard.class));

        assertEquals(HttpStatus.NOT_FOUND, controller.get("orders_http_api").getStatusCode());
        assertEquals("NO", jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE lower(table_name) = 'capability_tool_definition' AND lower(column_name) = 'asset_type'
                """, String.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO capability_tool_definition (name, title, description, source, asset_type, enabled, side_effect)
                VALUES ('legacy_null_type', '旧定义', '不能保存空资产类型', 'sdk', NULL, TRUE, 'READ_ONLY')
                """));

        jdbc.update("""
                INSERT INTO capability_tool_definition (name, title, description, source, enabled, side_effect)
                VALUES ('legacy_default_type', '默认分类旧定义', '由数据库默认值物化', 'sdk', TRUE, 'READ_ONLY')
                """);
        assertEquals("UNCLASSIFIED", jdbc.queryForObject(
                "SELECT asset_type FROM capability_tool_definition WHERE name = 'legacy_default_type'", String.class));
    }

    @Test
    void readsAnAcceptedBusinessMethodByItsStableQualifiedRuntimeReference() {
        insert("orders_read", "查询订单", 7L, "BUSINESS_METHOD", true, "正常声明");
        BusinessMethodCatalogInternalController controller = new BusinessMethodCatalogInternalController(
                service, mock(CapabilitySourceContractGuard.class));

        var response = controller.get("orders:orders_read");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("orders_read", response.getBody().name());
        assertEquals("orders:orders_read", response.getBody().qualifiedName());
    }

    private void insert(String name, String title, Long projectId, String assetType, boolean enabled, String description) {
        ToolDefinitionEntity entity = new ToolDefinitionEntity();
        entity.setName(name);
        entity.setTitle(title);
        entity.setDescription(description);
        entity.setSource("sdk");
        entity.setAssetType(assetType);
        entity.setEnabled(enabled);
        entity.setSideEffect("READ_ONLY");
        entity.setProjectId(projectId);
        entity.setProjectCode(projectId == 7L ? "orders" : "other");
        entity.setQualifiedName((projectId == 7L ? "orders" : "other") + ":" + name);
        entity.setSourceQualifiedName(entity.getQualifiedName());
        entity.setParametersJson("[]");
        tools.insert(entity);
    }

    private void createToolDefinitionTable() throws Exception {
        String baseline = Files.readString(Path.of("../sql/initV2.sql"));
        var table = Pattern.compile("(?is)CREATE TABLE(?: IF NOT EXISTS)? `?capability_tool_definition`?\\s*\\(.*?;")
                .matcher(baseline);
        assertTrue(table.find(), "baseline missing capability_tool_definition");
        String sql = table.group()
                .replaceAll("(?is)\\) ENGINE=.*?;", ");")
                .replaceAll("(?i)CHARACTER SET \\w+", "")
                .replaceAll("(?i)COLLATE \\w+", "")
                .replaceAll("(?i)(KEY\\s+)`([^`]+)`", "$1`capability_tool_definition_$2`")
                .replaceAll("(?i)\\bJSON\\b", "LONGTEXT");
        jdbc.execute(sql);
        var additions = Pattern.compile("CALL add_col_if_absent\\('capability_tool_definition',\\s*'([^']+)',\\s*'((?:''|[^'])*)'\\);", Pattern.DOTALL)
                .matcher(baseline);
        while (additions.find()) {
            String definition = additions.group(2)
                    .replace("''", "'")
                    .replaceAll("(?i)\\s+AFTER\\s+`[^`]+`", "")
                    .replaceAll("(?i)\\bJSON\\b", "LONGTEXT");
            jdbc.execute("ALTER TABLE `capability_tool_definition` ADD COLUMN IF NOT EXISTS `"
                    + additions.group(1) + "` " + definition);
        }
    }
}
