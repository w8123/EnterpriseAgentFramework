package com.enterprise.ai.capability.externalapi;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.capability.catalog.httpapi.*;
import com.enterprise.ai.common.capability.HttpApiFirstCallPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Execute the real owner migration twice, preserving the old public version and project selection. */
class OpenMeteoOfficialVersionMigrationPersistenceTest {
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private SqlSessionTemplate session;
    private final ObjectMapper json = new ObjectMapper();
    private static final String VERSION = "official-930063f12047-scalar-query-20261009";

    @BeforeEach
    void baselineAndExistingReference() throws Exception {
        source = new DriverManagerDataSource("jdbc:h2:mem:openmeteo_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        String baseline = Files.readString(Path.of("../sql/initV2.sql"));
        for (String table : List.of("capability_external_api_entry", "capability_external_api_version",
                "capability_external_api_operation", "capability_project_external_api", "capability_project_external_api_operation")) {
            var matcher = Pattern.compile("(?ism)CREATE TABLE IF NOT EXISTS `" + table + "`\\s*\\(.*?^\\)\\s*ENGINE=[^\\r\\n]*;").matcher(baseline);
            assertTrue(matcher.find(), "Missing current owning table " + table);
            jdbc.execute(matcher.group().replaceAll("(?im)^\\)\\s*ENGINE=[^\\r\\n]*;", ");")
                    .replaceAll("(?i)COLLATE \\w+", "")
                    .replaceAll("(?i)KEY `([^`]+)`", "KEY `" + table + "_$1`"));
        }
        jdbc.update("INSERT INTO capability_external_api_entry (id,entry_key,provider_id,source_id,title,summary,docs_url,auth_type,publication_status) VALUES (1,'open-meteo',1,1,'Open-Meteo','Public catalog fixture','https://open-meteo.com/en/docs','NONE','PUBLISHED')");
        jdbc.update("INSERT INTO capability_external_api_version (id,entry_id,version_key,base_url,publication_status) VALUES (1,1,'v1','https://api.open-meteo.com','PUBLISHED')");
        jdbc.update("INSERT INTO capability_external_api_operation (id,version_id,operation_key,title,http_method,path,side_effect) VALUES (1,1,'forecast','Legacy forecast','GET','/v1/forecast','READ_ONLY')");
        jdbc.update("INSERT INTO capability_project_external_api (id,project_id,project_code,entry_id,version_id,environment,status) VALUES (1,27,'existing-project',1,1,'dev','READY')");
        jdbc.update("INSERT INTO capability_project_external_api_operation (id,integration_id,operation_id) VALUES (1,1,1)");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (var mapper : List.of(ExternalApiEntryMapper.class, ExternalApiVersionMapper.class, ExternalApiOperationMapper.class)) configuration.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(configuration); factory.setDataSource(source);
        session = new SqlSessionTemplate(factory.getObject());
    }

    @AfterEach
    void close() {
        if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    @Test
    void twiceAddsOneExecutableOfficialVersionWithoutReplacingTheLegacySelection() throws Exception {
        migrate(); migrate();
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM capability_external_api_version WHERE entry_id=1", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM capability_external_api_operation", Integer.class));
        assertNull(jdbc.queryForObject("SELECT spec_hash FROM capability_external_api_version WHERE id=1", String.class));
        assertNull(jdbc.queryForObject("SELECT response_content_type FROM capability_external_api_operation WHERE id=1", String.class));
        assertEquals(1L, jdbc.queryForObject("SELECT version_id FROM capability_project_external_api WHERE id=1", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT operation_id FROM capability_project_external_api_operation WHERE id=1", Long.class));
        var entry = session.getMapper(ExternalApiEntryMapper.class).selectById(1L);
        Long versionId = jdbc.queryForObject("SELECT id FROM capability_external_api_version WHERE version_key=?", Long.class, VERSION);
        var version = session.getMapper(ExternalApiVersionMapper.class).selectById(versionId);
        var operation = session.getMapper(ExternalApiOperationMapper.class).selectById(
                jdbc.queryForObject("SELECT id FROM capability_external_api_operation WHERE version_id=?", Long.class, versionId));
        var project = new ScanProjectEntity(); project.setId(36L); project.setProjectCode("bzjs21"); project.setEnvironment("dev");
        var canonicalizer = new HttpApiContractCanonicalizer(json);
        var owner = new ApiMarketHttpApiBindingService(mock(HttpApiAssetService.class), mock(HttpApiAssetMapper.class),
                mock(HttpApiSourceBindingMapper.class), mock(ProjectExternalApiMapper.class), mock(ProjectExternalApiOperationMapper.class),
                mock(ExternalApiEntryMapper.class), mock(ExternalApiVersionMapper.class), mock(ExternalApiOperationMapper.class), canonicalizer, json);
        var observed = owner.prepare(project, entry, version, operation, 1L);
        var contract = json.readTree(canonicalizer.canonicalize(observed.scope(), observed.contract()).contractJson());
        assertNull(HttpApiFirstCallPolicy.unsupportedReason(contract));
        assertEquals("NONE", contract.path("authentication").path("state").asText());
        assertEquals("200", contract.path("responses").get(0).path("status").asText());
        var request = HttpApiFirstCallPolicy.bind(contract, Map.of(), Map.of("latitude", "52.52", "longitude", "13.41"), json);
        assertEquals("/v1/forecast", request.encodedRoute());
        assertEquals(Map.of("latitude", "52.52", "longitude", "13.41"), request.queryParams());
        assertThrows(IllegalArgumentException.class, () -> HttpApiFirstCallPolicy.bind(contract, Map.of(), Map.of("latitude", "52.52"), json));
        assertTrue(version.getOpenapiUrl().contains("930063f12047363861f775c685c16a98b52dbfe4"));
        var snapshot = json.readTree(Files.readAllBytes(Path.of("../docs/integrations/api-market/open-meteo-forecast-scalar-20261009.json")));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(snapshot))), version.getSpecHash());
    }

    @Test
    void doesNotInstallOfficialNoAuthProfileWhenThePublicEntryRequiresCredentials() throws Exception {
        jdbc.update("UPDATE capability_external_api_entry SET auth_type='BEARER' WHERE id=1");
        migrate();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM capability_external_api_version", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM capability_external_api_operation", Integer.class));
        assertEquals(1L, jdbc.queryForObject("SELECT version_id FROM capability_project_external_api WHERE id=1", Long.class));
    }

    private void migrate() throws Exception {
        byte[] sql = Files.readAllBytes(Path.of("../sql/upgrade-20261009-open-meteo-official-version.sql"));
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ByteArrayResource(sql), StandardCharsets.UTF_8));
        }
    }
}
