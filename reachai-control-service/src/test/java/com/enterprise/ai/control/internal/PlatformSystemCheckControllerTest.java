package com.enterprise.ai.control.internal;

import com.enterprise.ai.common.bootstrap.ReachAiBootstrapEnvironmentPostProcessor;
import com.enterprise.ai.common.config.ReachAiLocalDevelopmentDefaultsEnvironmentPostProcessor;
import com.enterprise.ai.control.client.knowledge.KnowledgeHealthClient;
import com.enterprise.ai.control.identity.PlatformAuthProperties;
import com.enterprise.ai.control.identity.PlatformDefaultAdminPasswordCheck;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.identity.PlatformUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformSystemCheckControllerTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final InternalServicesHealthController services = mock(InternalServicesHealthController.class);
    private final KnowledgeHealthClient knowledge = mock(KnowledgeHealthClient.class);
    private final PlatformUserMapper users = mock(PlatformUserMapper.class);
    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.internal:3306/reach_ai?useSSL=false");

    @Test
    void healthyGeneratedInstallationOnlyWarnsAboutTheDefaultAdminPassword() {
        allServices("UP");
        environment.getPropertySources().addLast(new MapPropertySource(
                ReachAiBootstrapEnvironmentPostProcessor.GENERATED_SECRETS_SOURCE,
                Map.of(ReachAiBootstrapEnvironmentPostProcessor.SECRETS_FILE_PROPERTY, "/srv/reachai/.reachai/secrets.properties")));
        admin("admin123");
        when(knowledge.info()).thenReturn(Map.of("dependencies", Map.of(
                "milvus", Map.of("enabled", true, "address", "localhost:19530", "reachable", false),
                "docling", Map.of("enabled", true, "address", "http://localhost:5001", "reachable", true))));

        Map<String, Object> report = controller().systemCheck();

        assertEquals("OK", item(report, "database").get("status"));
        assertTrue(String.valueOf(item(report, "database").get("detail")).endsWith("db.internal:3306/reach_ai"));
        assertEquals("OK", item(report, "runtime").get("status"));
        assertEquals("OK", item(report, "secrets").get("status"));
        assertTrue(String.valueOf(item(report, "secrets").get("detail")).contains("/srv/reachai/.reachai/secrets.properties"));
        assertEquals("WARN", item(report, "admin-password").get("status"));
        assertEquals("/settings/platform-users", item(report, "admin-password").get("link"));
        assertEquals("OFF", item(report, "milvus").get("status"));
        assertEquals("OK", item(report, "docling").get("status"));
        assertEquals(Map.of("OK", 8, "WARN", 1, "ERROR", 0, "OFF", 1), report.get("summary"));
    }

    @Test
    void downServiceAndPublicDevelopmentSecretsAreReportedWithFixes() {
        allServices("DOWN");
        environment.getPropertySources().addFirst(new MapPropertySource(
                ReachAiLocalDevelopmentDefaultsEnvironmentPostProcessor.PROPERTY_SOURCE_NAME,
                Map.of("reachai.internal.service-secret", "public")));
        admin("a-much-better-password");

        Map<String, Object> report = controller().systemCheck();

        assertEquals("ERROR", item(report, "knowledge").get("status"));
        assertTrue(String.valueOf(item(report, "knowledge").get("fix")).contains("./dev.sh status"));
        assertEquals("WARN", item(report, "secrets").get("status"));
        assertEquals("OK", item(report, "admin-password").get("status"));
        // Knowledge is down, so its dependencies are not probed.
        verify(knowledge, never()).info();
        assertEquals("WARN", item(report, "milvus").get("status"));
    }

    private PlatformSystemCheckController controller() {
        return new PlatformSystemCheckController(services, knowledge, environment,
                new PlatformDefaultAdminPasswordCheck(new PlatformAuthProperties(), users, encoder));
    }

    private void allServices(String status) {
        Map<String, Object> state = Map.of("status", status);
        when(services.health()).thenReturn(ResponseEntity.ok(Map.of("services", Map.of(
                "runtime", state, "capability", state, "model", state, "knowledge", state))));
    }

    private void admin(String password) {
        PlatformUserEntity admin = new PlatformUserEntity();
        admin.setUsername("admin");
        admin.setStatus("ACTIVE");
        admin.setPasswordHash(encoder.encode(password));
        when(users.selectOne(any())).thenReturn(admin);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> item(Map<String, Object> report, String key) {
        return ((List<Map<String, Object>>) report.get("items")).stream()
                .filter(entry -> key.equals(entry.get("key")))
                .findFirst()
                .orElseThrow();
    }
}
