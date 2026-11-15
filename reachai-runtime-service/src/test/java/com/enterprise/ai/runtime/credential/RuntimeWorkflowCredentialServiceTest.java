package com.enterprise.ai.runtime.credential;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowCredentialServiceTest {

    @Test
    void rejectsMissingCredentialSecret() {
        assertThrows(IllegalStateException.class, () -> new RuntimeWorkflowCredentialCipher(""));
        assertThrows(IllegalStateException.class, () -> new RuntimeWorkflowCredentialCipher("   "));
        assertTrue(new RuntimeWorkflowCredentialCipher("unit-test-secret")
                .encrypt("safe").startsWith("aesgcm:"));
    }

    @Test
    void createEncryptsSecretAndMasksResponse() {
        RuntimeWorkflowCredentialMapper mapper = mock(RuntimeWorkflowCredentialMapper.class);
        RuntimeWorkflowCredentialService service = new RuntimeWorkflowCredentialService(
                mapper,
                new RuntimeWorkflowCredentialCipher("unit-test-secret"),
                new ObjectMapper());

        RuntimeWorkflowCredentialRequest request = new RuntimeWorkflowCredentialRequest(
                null,
                "Orders API",
                "bearer",
                7L,
                "orders",
                null,
                null,
                Map.of("token", "secret-token-123"));

        RuntimeWorkflowCredentialView response = service.create(request);

        ArgumentCaptor<RuntimeWorkflowCredentialEntity> captor =
                ArgumentCaptor.forClass(RuntimeWorkflowCredentialEntity.class);
        verify(mapper).insert(captor.capture());
        RuntimeWorkflowCredentialEntity entity = captor.getValue();
        assertTrue(entity.getSecretJson().startsWith("aesgcm:"));
        assertFalse(entity.getSecretJson().contains("secret-token-123"));
        assertEquals("BEARER", entity.getType());
        assertEquals("PROJECT", entity.getScope());
        assertEquals("ACTIVE", entity.getStatus());
        assertEquals("se****23", response.secretPreview().get("token"));
    }

    @Test
    void updateKeepsExistingSecretWhenSecretIsOmitted() {
        RuntimeWorkflowCredentialMapper mapper = mock(RuntimeWorkflowCredentialMapper.class);
        RuntimeWorkflowCredentialCipher cipher = new RuntimeWorkflowCredentialCipher("unit-test-secret");
        RuntimeWorkflowCredentialEntity existing = new RuntimeWorkflowCredentialEntity();
        existing.setId(2L);
        existing.setCredentialRef("cred_orders");
        existing.setName("Orders API");
        existing.setType("BEARER");
        existing.setStatus("ACTIVE");
        existing.setScope("PROJECT");
        existing.setSecretJson(cipher.encrypt("{\"token\":\"secret-token-123\"}"));
        when(mapper.selectById(2L)).thenReturn(existing);
        RuntimeWorkflowCredentialService service = new RuntimeWorkflowCredentialService(
                mapper,
                cipher,
                new ObjectMapper());

        RuntimeWorkflowCredentialView response = service.update(2L, new RuntimeWorkflowCredentialRequest(
                "cred_orders",
                "Orders API v2",
                "api_key_header",
                7L,
                "orders",
                "project",
                "active",
                null));

        assertEquals("Orders API v2", response.name());
        assertEquals("API_KEY_HEADER", existing.getType());
        assertEquals("se****23", response.secretPreview().get("token"));
        verify(mapper).updateById(existing);
    }

    @Test
    void aSignInCredentialShowsItsSettingsButNeverWhatItSends() {
        RuntimeWorkflowCredentialMapper mapper = mock(RuntimeWorkflowCredentialMapper.class);
        RuntimeWorkflowCredentialCipher cipher = new RuntimeWorkflowCredentialCipher("unit-test-secret");
        RuntimeWorkflowCredentialEntity existing = new RuntimeWorkflowCredentialEntity();
        existing.setId(2L);
        existing.setCredentialRef("cred_orders");
        existing.setName("Orders API");
        existing.setType("API_KEY_HEADER");
        existing.setStatus("ACTIVE");
        existing.setScope("PROJECT");
        existing.setProjectId(7L);
        existing.setProjectCode("orders");
        existing.setSecretJson(cipher.encrypt("{\"headerName\":\"token\",\"apiKey\":\"old-token\"}"));
        when(mapper.selectById(2L)).thenReturn(existing);
        when(mapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowCredentialEntity>>any()))
                .thenReturn(existing);
        RuntimeWorkflowCredentialService service = new RuntimeWorkflowCredentialService(mapper, cipher, new ObjectMapper());

        RuntimeWorkflowCredentialView rotated = service.update(2L, new RuntimeWorkflowCredentialRequest("cred_orders",
                "Orders API", "API_KEY_HEADER", 7L, "orders", "PROJECT", "ACTIVE",
                Map.of("headerName", "token", "login", Map.of("url", "https://erp.example.com/login",
                        "body", "{\"password\":\"p-123456\"}", "ttlMinutes", 60))));

        var resolved = service.resolveProjectCredential("cred_orders", 7L, "orders").orElseThrow();
        assertEquals(60, WorkflowCredentialTypes.login(resolved.secret()).get("ttlMinutes"));
        Map<?, ?> shown = (Map<?, ?>) rotated.secretPreview().get("login");
        assertEquals("https://erp.example.com/login", shown.get("url"));
        assertFalse(String.valueOf(rotated.secretPreview()).contains("p-123456"));
        assertFalse(rotated.secretPreview().containsKey("apiKey"));
    }

    @Test
    void signInSettingsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowCredentialTypes.validateAndNormalizeSecret("BEARER",
                Map.of("login", Map.of("url", "ftp://erp/login"))));
        assertThrows(IllegalArgumentException.class, () -> WorkflowCredentialTypes.validateAndNormalizeSecret("BEARER",
                Map.of("login", Map.of("url", "https://erp/login", "tokenPath", "data[0]"))));
        assertThrows(IllegalArgumentException.class, () -> WorkflowCredentialTypes.validateAndNormalizeSecret("BEARER",
                Map.of("token", "")));
    }

    @Test
    void deleteDisablesCredentialInsteadOfRemovingRow() {
        RuntimeWorkflowCredentialMapper mapper = mock(RuntimeWorkflowCredentialMapper.class);
        RuntimeWorkflowCredentialEntity existing = new RuntimeWorkflowCredentialEntity();
        existing.setId(2L);
        existing.setStatus("ACTIVE");
        when(mapper.selectById(2L)).thenReturn(existing);
        RuntimeWorkflowCredentialService service = new RuntimeWorkflowCredentialService(
                mapper,
                new RuntimeWorkflowCredentialCipher("unit-test-secret"),
                new ObjectMapper());

        service.delete(2L);

        assertEquals("DISABLED", existing.getStatus());
        verify(mapper).updateById(existing);
    }

    @Test
    void resolveDecryptsRuntimeSecret() {
        RuntimeWorkflowCredentialCipher cipher = new RuntimeWorkflowCredentialCipher("unit-test-secret");
        RuntimeWorkflowCredentialMapper mapper = mock(RuntimeWorkflowCredentialMapper.class);
        RuntimeWorkflowCredentialEntity entity = new RuntimeWorkflowCredentialEntity();
        entity.setCredentialRef("cred_orders");
        entity.setName("Orders API");
        entity.setType("API_KEY_HEADER");
        entity.setStatus("ACTIVE");
        entity.setScope("PROJECT");
        entity.setProjectCode("orders");
        entity.setSecretJson(cipher.encrypt("{\"headerName\":\"X-API-Key\",\"apiKey\":\"abc123\"}"));
        when(mapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowCredentialEntity>>any()))
                .thenReturn(entity);
        RuntimeWorkflowCredentialService service = new RuntimeWorkflowCredentialService(mapper, cipher, new ObjectMapper());

        var runtime = service.resolve("cred_orders", WorkflowExecutionIdentity.fromAgent(null, "orders"));

        assertTrue(runtime.isPresent());
        assertEquals("API_KEY_HEADER", runtime.get().type());
        assertEquals("X-API-Key", runtime.get().secret().get("headerName"));
        assertEquals("abc123", runtime.get().secret().get("apiKey"));
    }

    @Test
    void resolveFailsClosedWhenProjectIdentityMissingForProjectScope() {
        RuntimeWorkflowCredentialCipher cipher = new RuntimeWorkflowCredentialCipher("unit-test-secret");
        RuntimeWorkflowCredentialMapper mapper = mock(RuntimeWorkflowCredentialMapper.class);
        RuntimeWorkflowCredentialEntity entity = new RuntimeWorkflowCredentialEntity();
        entity.setCredentialRef("cred_orders");
        entity.setType("BEARER");
        entity.setStatus("ACTIVE");
        entity.setScope("PROJECT");
        entity.setProjectCode("orders");
        entity.setSecretJson(cipher.encrypt("{\"token\":\"secret-token-123\"}"));
        when(mapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowCredentialEntity>>any()))
                .thenReturn(entity);
        RuntimeWorkflowCredentialService service = new RuntimeWorkflowCredentialService(mapper, cipher, new ObjectMapper());

        assertTrue(service.resolve("cred_orders", WorkflowExecutionIdentity.untrustedDebug()).isEmpty());
        assertTrue(service.resolve("cred_orders", WorkflowExecutionIdentity.fromAgent(null, "other")).isEmpty());
        assertTrue(service.resolve("cred_orders", WorkflowExecutionIdentity.fromAgent(null, "orders")).isPresent());
    }

    @Test
    void createNormalizesCanonicalTypesAndRejectsInvalidSecretShape() {
        RuntimeWorkflowCredentialMapper mapper = mock(RuntimeWorkflowCredentialMapper.class);
        RuntimeWorkflowCredentialService service = new RuntimeWorkflowCredentialService(
                mapper,
                new RuntimeWorkflowCredentialCipher("unit-test-secret"),
                new ObjectMapper());

        RuntimeWorkflowCredentialView created = service.create(new RuntimeWorkflowCredentialRequest(
                null, "Custom", "CUSTOM_HEADERS", null, null, "GLOBAL", null,
                Map.of("headers", Map.of("X-Trace", "abc"))));
        assertEquals("CUSTOM_HEADERS", created.type());

        try {
            service.create(new RuntimeWorkflowCredentialRequest(
                    null, "Bad", "API_KEY_QUERY", null, null, "GLOBAL", null,
                    Map.of("paramName", "key")));
            assertTrue(false, "expected missing apiKey to fail");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("apiKey"));
        }
    }
}
