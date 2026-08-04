package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationClaims;
import com.enterprise.ai.reach.sdk.auth.ReachAiInvocationToken;
import com.enterprise.ai.reach.sdk.auth.ReachAiSignatureHeaders;
import com.enterprise.ai.reach.sdk.auth.ReachAiSigner;
import com.enterprise.ai.reach.sdk.client.ReachAiClientConfig;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReachCapabilityEndpointTest {

    @Test
    void delegatesInvokeRequestToCapabilityInvoker() {
        ReachCapabilityEndpoint endpoint = new ReachCapabilityEndpoint(
                new ReachCapabilityInvoker(new Object[]{new ContractCapability()}));
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("contractNo", "HT-002");

        Object result = endpoint.invoke("contract.query", arguments);

        assertEquals("contract:HT-002", result);
    }

    @Test
    void rejectsInvocationWithoutReachAiInvocationToken() {
        ReachAiRegistryProperties properties = invocationProperties();
        ReachCapabilityEndpoint endpoint = new ReachCapabilityEndpoint(
                new ReachCapabilityInvoker(new Object[]{new ContractCapability()}),
                new ReachCapabilityInvocationVerifier(properties),
                Collections.<ReachAiSecurityContextBridge>emptyList());

        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("contractNo", "HT-002");

        assertThrows(ReachAiInvocationUnauthorizedException.class, () -> endpoint.invoke(
                "contract.query", null, arguments));
    }

    @Test
    void verifiesInvocationTokenAndExposesDelegatedUserContext() {
        ReachAiRegistryProperties properties = invocationProperties();
        AtomicReference<ReachAiInvocationContext> bridged = new AtomicReference<ReachAiInvocationContext>();
        ReachCapabilityEndpoint endpoint = new ReachCapabilityEndpoint(
                new ReachCapabilityInvoker(new Object[]{new ContextAwareCapability()}),
                new ReachCapabilityInvocationVerifier(properties),
                Collections.<ReachAiSecurityContextBridge>singletonList(new ReachAiSecurityContextBridge() {
                    @Override
                    public Object runWith(ReachAiInvocationContext context, Invocation invocation) throws Exception {
                        bridged.set(context);
                        return invocation.proceed();
                    }
                }));
        String token = ReachAiInvocationToken.sign("secret", ReachAiInvocationClaims.builder()
                .projectCode("bzsdk")
                .appKey("demo-key")
                .capabilityName("contract.context")
                .externalUserId("ADMIN001")
                .globalUserId("emp-0001")
                .agentId("team-agent")
                .sessionId("session-1")
                .traceId("trace-1")
                .roles(Collections.singletonList("admin"))
                .build(), System.currentTimeMillis(), 120);

        Object result = endpoint.invoke("contract.context", token, Collections.<String, Object>emptyMap());

        assertEquals("ADMIN001|team-agent", result);
        assertEquals("ADMIN001", bridged.get().getExternalUserId());
        assertEquals("team-agent", bridged.get().getAgentId());
    }

    @Test
    void syncEndpointVerifiesPlatformSignatureBeforeScanning() {
        ReachAiRegistryProperties properties = registryProperties();
        RecordingRegistryClient registryClient = new RecordingRegistryClient(properties);
        ReachCapabilitySyncEndpoint endpoint = new ReachCapabilitySyncEndpoint(
                registryClient,
                new ReachAiRegistryRequestVerifier(properties));
        ReachAiSignatureHeaders headers = ReachAiSigner.sign(registryClientConfig(properties),
                String.valueOf(System.currentTimeMillis()), "nonce-1");

        ReachAiRegistryClient.ManualCapabilitySyncResponse response = endpoint.sync(
                headers.getAppKey(),
                headers.getTimestamp(),
                headers.getNonce(),
                headers.getSignature());

        assertEquals(1, registryClient.scanCalls);
        assertEquals(2, response.getCapabilityCount());
        assertEquals(ReachAiRegistryClient.CALLBACK_SYNC_SOURCE, registryClient.lastSyncSource);
    }

    @Test
    void syncEndpointMarksReachAiInitiatedManualScanAsCallbackEvidence() {
        ReachAiRegistryProperties properties = registryProperties();
        RecordingRegistryClient registryClient = new RecordingRegistryClient(properties);
        ReachCapabilitySyncEndpoint endpoint = new ReachCapabilitySyncEndpoint(
                registryClient,
                new ReachAiRegistryRequestVerifier(properties));
        ReachAiSignatureHeaders headers = ReachAiSigner.sign(registryClientConfig(properties),
                String.valueOf(System.currentTimeMillis()), "nonce-callback");

        endpoint.sync(
                headers.getAppKey(),
                headers.getTimestamp(),
                headers.getNonce(),
                headers.getSignature());

        assertEquals(ReachAiRegistryClient.CALLBACK_SYNC_SOURCE, registryClient.lastSyncSource);
    }

    @Test
    void syncEndpointRejectsInvalidPlatformSignature() {
        ReachAiRegistryProperties properties = registryProperties();
        RecordingRegistryClient registryClient = new RecordingRegistryClient(properties);
        ReachCapabilitySyncEndpoint endpoint = new ReachCapabilitySyncEndpoint(
                registryClient,
                new ReachAiRegistryRequestVerifier(properties));

        assertThrows(ReachAiInvocationUnauthorizedException.class, () -> endpoint.sync(
                "bad-key",
                String.valueOf(System.currentTimeMillis()),
                "nonce-1",
                "bad-signature"));
        assertEquals(0, registryClient.scanCalls);
    }

    @Test
    void syncEndpointRejectsReplayOfAnAcceptedSignedRequest() {
        ReachAiRegistryProperties properties = registryProperties();
        RecordingRegistryClient registryClient = new RecordingRegistryClient(properties);
        ReachAiRegistryRequestVerifier verifier = new ReachAiRegistryRequestVerifier(properties);
        ReachCapabilitySyncEndpoint endpoint = new ReachCapabilitySyncEndpoint(
                registryClient,
                verifier);
        ReachAiSignatureHeaders headers = ReachAiSigner.sign(registryClientConfig(properties),
                String.valueOf(System.currentTimeMillis()), "nonce-replay");

        endpoint.sync(
                headers.getAppKey(),
                headers.getTimestamp(),
                headers.getNonce(),
                headers.getSignature());

        assertThrows(ReachAiInvocationUnauthorizedException.class, () -> endpoint.sync(
                headers.getAppKey(),
                headers.getTimestamp(),
                headers.getNonce(),
                headers.getSignature()));
        assertEquals(1, registryClient.scanCalls);
    }

    private ReachAiRegistryProperties invocationProperties() {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getProject().setCode("bzsdk");
        properties.getRegistry().setAppKey("demo-key");
        properties.getRegistry().setAppSecret("secret");
        properties.getCapability().setRequireInvocationToken(true);
        return properties;
    }

    private ReachAiRegistryProperties registryProperties() {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getProject().setCode("bzsdk");
        properties.getRegistry().setUrl("https://reachai.example.com");
        properties.getRegistry().setAppKey("demo-key");
        properties.getRegistry().setAppSecret("secret");
        return properties;
    }

    private ReachAiClientConfig registryClientConfig(ReachAiRegistryProperties properties) {
        return ReachAiClientConfig.builder()
                .endpoint(properties.getRegistry().getUrl())
                .projectCode(properties.getProject().getCode())
                .appKey(properties.getRegistry().getAppKey())
                .appSecret(properties.getRegistry().getAppSecret())
                .build();
    }

    static class ContractCapability {
        @ReachCapability(name = "contract.query")
        public String query(@ReachParam(name = "contractNo", required = true) String contractNo) {
            return "contract:" + contractNo;
        }
    }

    static class ContextAwareCapability {
        @ReachCapability(name = "contract.context")
        public String query() {
            ReachAiInvocationContext context = ReachAiInvocationContextHolder.getRequired();
            return context.getExternalUserId() + "|" + context.getAgentId();
        }
    }

    static class RecordingRegistryClient extends ReachAiRegistryClient {
        private int scanCalls;
        private String lastSyncSource;

        RecordingRegistryClient(ReachAiRegistryProperties properties) {
            super(properties, new ReachCapabilityBeanScanner(new Object[0]), new ReachAiRegistryClientTest.RecordingTransport());
        }

        @Override
        public ManualCapabilitySyncResponse scanAndSyncCapabilities() {
            scanCalls++;
            return new ManualCapabilitySyncResponse("instance-1", 2, "{}");
        }

        @Override
        ManualCapabilitySyncResponse scanAndSyncCapabilitiesFromPlatformCallback() {
            scanCalls++;
            lastSyncSource = ReachAiRegistryClient.CALLBACK_SYNC_SOURCE;
            return new ManualCapabilitySyncResponse("instance-1", 2, "{}");
        }
    }
}
