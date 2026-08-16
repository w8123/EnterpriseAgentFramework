package com.enterprise.ai.agent.registry;

import com.enterprise.ai.reach.sdk.auth.ReachAiProjectRequestHeaders;
import com.enterprise.ai.reach.sdk.auth.ReachAiProjectRequestSigner;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistryProjectRequestVerificationServiceTest {

    private static final String PATH =
            "/api/knowledge-ingress/projects/orders/biz-index/orders_idx/batch";

    @Test
    void verifiesBodyBoundCredentialAndConsumesNonce() {
        RegistryCredentialMapper mapper = mock(RegistryCredentialMapper.class);
        RegistryProjectRequestNonceStore nonceStore = mock(RegistryProjectRequestNonceStore.class);
        when(mapper.selectOne(any())).thenReturn(credential());
        when(nonceStore.tryConsume(any(), any(), any(), anyLong(), anyLong(), anyInt()))
                .thenReturn(true);
        RegistryProjectRequestVerificationService service = new RegistryProjectRequestVerificationService(
                mapper, nonceStore, 300, 600, 1000);
        byte[] body = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
        ReachAiProjectRequestHeaders signed = ReachAiProjectRequestSigner.sign(
                "rak_orders", "ras_orders", "orders", "POST", PATH, body);

        var verified = service.verify(request(signed, "POST", PATH));

        assertEquals(9L, verified.projectId());
        assertEquals(3L, verified.credentialId());
        assertEquals("orders", verified.projectCode());
    }

    @Test
    void rejectsReplayAndSignatureCopiedToAnotherPath() {
        RegistryCredentialMapper mapper = mock(RegistryCredentialMapper.class);
        RegistryProjectRequestNonceStore nonceStore = mock(RegistryProjectRequestNonceStore.class);
        when(mapper.selectOne(any())).thenReturn(credential());
        when(nonceStore.tryConsume(any(), any(), any(), anyLong(), anyLong(), anyInt()))
                .thenReturn(false);
        RegistryProjectRequestVerificationService service = new RegistryProjectRequestVerificationService(
                mapper, nonceStore, 300, 600, 1000);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        ReachAiProjectRequestHeaders signed = ReachAiProjectRequestSigner.sign(
                "rak_orders", "ras_orders", "orders", "POST", PATH, body);

        assertThrows(IllegalArgumentException.class,
                () -> service.verify(request(signed, "POST", PATH)));

        when(nonceStore.tryConsume(any(), any(), any(), anyLong(), anyLong(), anyInt()))
                .thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.verify(request(signed, "POST", PATH.replace("batch", "upsert"))));
    }

    private static RegistryProjectRequestVerificationService.ProjectRequest request(
            ReachAiProjectRequestHeaders headers, String method, String path) {
        return new RegistryProjectRequestVerificationService.ProjectRequest(
                "orders", headers.getAppKey(), method, path,
                headers.getTimestamp(), headers.getNonce(), headers.getBodySha256(),
                headers.getSignature());
    }

    private static RegistryCredentialEntity credential() {
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setId(3L);
        credential.setProjectId(9L);
        credential.setProjectCode("orders");
        credential.setAppKey("rak_orders");
        credential.setAppSecret("ras_orders");
        credential.setStatus("ACTIVE");
        credential.setExpiresAt(LocalDateTime.now().plusHours(1));
        return credential;
    }
}
