package com.enterprise.ai.capability.catalog.scan;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.agent.capability.catalog.scan.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CapabilitySourcePagePersistenceTest {
    @Test
    void filtersBeforePagingAndRetainsWholeProjectCountsAndModuleLabels() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:source_page_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        createTable(jdbc, "capability_scan_project", ScanProjectEntity.class);
        createTable(jdbc, "capability_scan_project_tool", ScanProjectToolEntity.class);
        createTable(jdbc, "capability_scan_module", ScanModuleEntity.class);
        jdbc.execute("CREATE TABLE capability_tool_definition(id BIGINT, name VARCHAR(200))");
        jdbc.update("INSERT INTO capability_scan_project(id,name,project_code) VALUES (7,'Orders','orders'),(8,'Other','other')");
        jdbc.update("INSERT INTO capability_scan_module(id,project_id,name,display_name) VALUES (10,7,'orders','订单模块'),(20,8,'secret','他项目模块')");
        for (int i = 1; i <= 31; i++) {
            jdbc.update("INSERT INTO capability_scan_project_tool(id,project_id,module_id,name,title,removed_from_source) VALUES (?,?,?,?,?,?)",
                    i, 7, i <= 25 ? 10 : null, "source-" + i, "来源-" + i, i == 31);
        }
        jdbc.update("INSERT INTO capability_scan_project_tool(id,project_id,module_id,name,title) VALUES (99,8,20,'other','来源-99')");
        var configuration = new MybatisConfiguration();
        configuration.addMapper(ScanProjectMapper.class); configuration.addMapper(ScanProjectToolMapper.class); configuration.addMapper(ScanModuleMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        var tools = spy(session.getMapper(ScanProjectToolMapper.class));
        var service = new CapabilityScanProjectCatalogService(session.getMapper(ScanProjectMapper.class), tools,
                session.getMapper(ScanModuleMapper.class), null, null, null, null, null, null,
                new ObjectMapper(), null, null, null, null);
        var page = service.sourcePage(7L, 2, 5, "来源-", 10L, false, null, null);
        assertEquals(25, page.total()); assertEquals(2, page.current()); assertEquals(5, page.records().size());
        assertEquals(List.of(6L,7L,8L,9L,10L), page.records().stream().map(ScanProjectToolEntity::getId).toList());
        assertEquals(31, page.stats().total()); assertEquals(1, page.stats().removed());
        assertEquals("订单模块", page.modules().get(0).moduleDisplayName()); assertEquals(25, page.modules().get(0).count());
        assertEquals(6, page.modules().get(1).count()); assertNull(page.modules().get(1).moduleId());
        verify(tools, times(1)).selectList(argThat(wrapper -> wrapper.getSqlSegment().contains("LIMIT 5 OFFSET 5")));
        clearInvocations(tools);
        var unfiltered = service.sourcePage(7L, 1, 5, "  ", null, false, List.of(), null);
        assertEquals(31, unfiltered.total()); assertEquals(1, unfiltered.stats().removed());
        verify(tools, never()).selectCount(any());
        verify(tools).selectMaps(argThat(wrapper -> wrapper.getSqlSelect().contains("removed_count")));
        assertEquals(0, service.sourcePage(8L, 1, 20, null, null, false, List.of(100L), null).total());
        var focus = service.sourcePage(7L, 1, 5, null, null, false, null, 22L);
        assertEquals(5, focus.current()); assertTrue(focus.records().stream().anyMatch(row -> row.getId() == 22));
        var deepLink = service.sourcePage(7L, 1, 20, null, null, false, List.of(30L,99L), null);
        assertEquals(1, deepLink.total()); assertEquals(30L, deepLink.records().get(0).getId());
        assertEquals(6, service.sourcePage(7L, 1, 20, null, null, true, null, null).total());
        assertEquals(0, service.sourcePage(7L, 1, 20, "%", null, false, null, null).total());
        assertThrows(IllegalArgumentException.class, () -> service.sourcePage(7L, 1, 20, null, 10L, true, null, null));
    }

    private static void createTable(JdbcTemplate jdbc, String name, Class<?> entity) {
        var columns = new java.util.ArrayList<String>();
        for (var field : entity.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            String column = field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
            String type = field.getType() == Long.class ? "BIGINT" : field.getType() == Integer.class ? "INT"
                    : field.getType() == Boolean.class ? "BOOLEAN" : field.getType() == java.time.LocalDateTime.class ? "TIMESTAMP" : "VARCHAR(4096)";
            columns.add(column + " " + type + (column.equals("id") ? " PRIMARY KEY" : ""));
        }
        jdbc.execute("CREATE TABLE " + name + "(" + String.join(",", columns) + ")");
    }
}
