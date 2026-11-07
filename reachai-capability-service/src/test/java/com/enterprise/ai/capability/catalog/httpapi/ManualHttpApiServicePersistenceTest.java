package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.common.capability.HttpApiConsolePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Manual HTTP API source on real H2/MyBatis: creation, confirmation, survival across scans and removal. */
class ManualHttpApiServicePersistenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private HttpApiCatalogService catalog;
    private HttpApiAssetService assets;
    private HttpApiInventoryIntakeService intake;
    private ManualHttpApiService manual;
    private TransactionTemplate tx;

    @BeforeEach
    void database() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:manual_http_api_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        for (String function : List.of("JSON_VALID:jsonValid", "JSON_TYPE:jsonType", "SHA2:sha2")) {
            String[] parts = function.split(":");
            jdbc.execute("CREATE ALIAS " + parts[0] + " FOR '" + HttpApiSqlTestFunctions.class.getName() + "." + parts[1] + "'");
        }
        for (String table : List.of("capability_scan_project", "capability_scan_module", "capability_catalog_group_member",
                "capability_http_api_asset", "capability_http_api_source_binding", "capability_http_api_inventory_state",
                "capability_http_api_inventory_member", "capability_http_api_acceptance")) createTable(table);
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(ScanProjectMapper.class, HttpApiAssetMapper.class, HttpApiSourceBindingMapper.class,
                HttpApiInventoryStateMapper.class, HttpApiInventoryMemberMapper.class, HttpApiAcceptanceMapper.class)) config.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        var assetMapper = session.getMapper(HttpApiAssetMapper.class);
        var bindings = session.getMapper(HttpApiSourceBindingMapper.class);
        var inventories = session.getMapper(HttpApiInventoryStateMapper.class);
        var members = session.getMapper(HttpApiInventoryMemberMapper.class);
        var projects = session.getMapper(ScanProjectMapper.class);
        assets = new HttpApiAssetService(assetMapper, bindings, new HttpApiContractCanonicalizer(json),
                org.mockito.Mockito.mock(com.enterprise.ai.capability.catalog.enrichment.CapabilityCatalogGroupingService.class));
        var states = new HttpApiInventoryStateService(inventories, members);
        intake = new HttpApiInventoryIntakeService(assets, bindings, states);
        manual = new ManualHttpApiService(projects, assets, bindings, states, json);
        catalog = new HttpApiCatalogService(assetMapper, bindings, inventories, members,
                session.getMapper(HttpApiAcceptanceMapper.class), projects, json, new HttpApiContractCanonicalizer(json));
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.update("INSERT INTO capability_scan_project (id, name, project_code, environment, base_url, context_path, status) "
                + "VALUES (41, 'orders', 'orders', 'dev', 'https://unused.invalid', '/admin-api', 'scanned')");
    }

    @Test
    void manualGetIsConfirmedAcceptableCallableAndSurvivesCompleteScans() {
        var result = tx.execute(status -> manual.save(41, new ManualHttpApiService.Request("get", "/erp/stock/{id}",
                null, "curl", List.of(new ManualHttpApiService.Parameter("warehouseId", "QUERY", "integer", false, "仓库")),
                null, null, new ManualHttpApiService.Authentication("BEARER", null), List.of())));
        assertNotNull(result);
        assertTrue(result.created());

        var detail = catalog.detail(result.apiId());
        assertEquals("/admin-api/erp/stock/{id}", detail.summary().routeTemplate());
        assertEquals("DISCOVERED", detail.summary().sourceStatus());
        assertTrue(detail.summary().sourceConfirmed(), detail.summary().sourceReason());
        assertEquals(List.of("MANUAL"), detail.summary().sourceKinds());
        assertEquals("粘贴 curl 添加", detail.sources().get(0).sourceLocation());

        var accepted = catalog.accept(result.apiId(), detail.summary().sourceSetRevision(), "actor-1");
        assertEquals("ACCEPTED", accepted.summary().sourceStatus());
        assertNull(HttpApiConsolePolicy.unsupportedReason(accepted.acceptedContract()));

        // A complete, empty OpenAPI inventory and Starter inventory never tombstone the manual source.
        var scope = new HttpApiServiceScope(41L, "orders", "dev");
        tx.execute(status -> intake.observe(scope, HttpApiSourceKind.OPENAPI_SCAN, true, true, List.of(), null));
        tx.execute(status -> intake.observe(scope, HttpApiSourceKind.STARTER_MVC, true, true, List.of(), null));
        assertEquals("ACCEPTED", catalog.detail(result.apiId()).summary().sourceStatus());

        assertEquals(Boolean.TRUE, tx.execute(status -> manual.remove(41, result.apiId())));
        assertEquals("SOURCE_MISSING", catalog.detail(result.apiId()).summary().sourceStatus());
        assertEquals(Boolean.FALSE, tx.execute(status -> manual.remove(41, result.apiId())));
    }

    @Test
    void savingTheSameRouteUpdatesInsteadOfDuplicatingAndWritesCarryABody() {
        var body = json.createObjectNode().put("type", "object");
        body.putObject("properties").putObject("name").put("type", "string");
        var first = tx.execute(status -> manual.save(41, new ManualHttpApiService.Request("POST", "erp/customer/create/",
                null, "form", List.of(), new ManualHttpApiService.RequestBody(null, null, body), null, null, List.of())));
        var second = tx.execute(status -> manual.save(41, new ManualHttpApiService.Request("POST", "/erp/customer/create",
                "IRREVERSIBLE", "form", List.of(), new ManualHttpApiService.RequestBody("application/json", true, body), "object",
                new ManualHttpApiService.Authentication("API_KEY_HEADER", "X-Api-Key"), List.of())));
        assertEquals(first.apiId(), second.apiId());
        assertFalse(second.created());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM capability_http_api_source_binding WHERE source_kind='MANUAL'", Integer.class));
        var detail = catalog.detail(second.apiId());
        assertTrue(detail.summary().sourceConfirmed(), detail.summary().sourceReason());
        assertEquals("IRREVERSIBLE", detail.contract().path("sideEffect").asText());
        assertEquals("x-api-key", detail.contract().path("authentication").path("requiredHeaderNames").get(0).asText());
    }

    @Test
    void rejectsUnsafeOrMalformedInput() {
        for (var request : List.of(
                new ManualHttpApiService.Request("TRACE", "/a", null, null, null, null, null, null, null),
                new ManualHttpApiService.Request("GET", "https://erp.example.com/a", null, null, null, null, null, null, null),
                new ManualHttpApiService.Request("GET", "/a?b=1", null, null, null, null, null, null, null),
                new ManualHttpApiService.Request("GET", "/a/../b", null, null, null, null, null, null, null),
                new ManualHttpApiService.Request("GET", "/a", null, null,
                        List.of(new ManualHttpApiService.Parameter("x", "HEADER", "string", false, null)), null, null, null, null),
                new ManualHttpApiService.Request("GET", "/a", null, null, List.of(), new ManualHttpApiService.RequestBody(null, null,
                        json.createObjectNode().put("type", "object")), null, null, null),
                new ManualHttpApiService.Request("GET", "/a", null, null, null, null, null,
                        new ManualHttpApiService.Authentication("API_KEY_HEADER", "bad header"), null))) {
            assertThrows(IllegalArgumentException.class, () -> tx.execute(status -> manual.save(41, request)), request.toString());
        }
        assertThrows(ManualHttpApiService.NotFound.class, () -> tx.execute(status -> manual.save(999,
                new ManualHttpApiService.Request("GET", "/a", null, null, null, null, null, null, null))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM capability_http_api_source_binding", Integer.class));
    }

    private void createTable(String table) throws Exception {
        String baseline = Files.readString(Path.of("../sql/initV2.sql"));
        var match = Pattern.compile("(?is)CREATE TABLE(?: IF NOT EXISTS)? `?" + table + "`?\\s*\\(.*?;").matcher(baseline);
        assertTrue(match.find(), "baseline missing " + table);
        jdbc.execute(match.group().replaceAll("(?is)\\) ENGINE=.*?;", ");")
                .replaceAll("(?i)CHARACTER SET \\w+", "")
                .replaceAll("(?i)COLLATE \\w+", "")
                .replaceAll("(?i)(KEY\\s+)`([^`]+)`", "$1`" + table + "_$2`")
                .replaceAll("(?i)\\bJSON\\b", "LONGTEXT"));
        var additions = Pattern.compile("CALL add_col_if_absent\\('" + table + "', '([^']+)', '((?:''|[^'])*)'\\);").matcher(baseline);
        while (additions.find()) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS `" + additions.group(1) + "` "
                    + additions.group(2).replace("''", "'").replaceAll("(?i) AFTER `[^`]+`", ""));
        }
    }
}
