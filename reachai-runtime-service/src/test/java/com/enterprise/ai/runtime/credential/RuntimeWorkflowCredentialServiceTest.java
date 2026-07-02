package com.enterprise.ai.runtime.credential;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowCredentialServiceTest {

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
        entity.setSecretJson(cipher.encrypt("{\"headerName\":\"X-API-Key\",\"apiKey\":\"abc123\"}"));
        when(mapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<RuntimeWorkflowCredentialEntity>>any()))
                .thenReturn(entity);
        RuntimeWorkflowCredentialService service = new RuntimeWorkflowCredentialService(mapper, cipher, new ObjectMapper());

        var runtime = service.resolve("cred_orders", null, "orders");

        assertTrue(runtime.isPresent());
        assertEquals("X-API-Key", runtime.get().secret().get("headerName"));
        assertEquals("abc123", runtime.get().secret().get("apiKey"));
    }
}
