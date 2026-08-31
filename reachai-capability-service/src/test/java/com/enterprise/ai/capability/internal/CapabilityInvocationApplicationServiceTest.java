package com.enterprise.ai.capability.internal;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityInvocationApplicationServiceTest {

    @Test
    void returnsCorrelatedSafeSuccessThroughExactlyOneInvoker() {
        CapabilityInvocationAssetResolver resolver = mock(CapabilityInvocationAssetResolver.class);
        CapabilityInvocationAsset asset = asset("CATALOG_HTTP", true);
        when(resolver.require("orders:queryOrder")).thenReturn(asset);
        CapabilityInvoker invoker = mock(CapabilityInvoker.class);
        when(invoker.supports(asset)).thenReturn(true);
        when(invoker.invoke(any(), any())).thenReturn(Map.of(
                "success", true,
                "data", Map.of("orderStatus", "PAID"),
                "metadata", Map.of("statusCode", 200, "Authorization", "must-not-leak")));
        CapabilityInvocationApplicationService service = service(resolver, invoker);

        CapabilityInvocationResponse response = service.invoke(request(
                "inv-1", System.currentTimeMillis() + 30_000));

        assertEquals("inv-1", response.invocationId());
        assertEquals(CapabilityInvocationStatus.SUCCEEDED, response.status());
        assertEquals(CapabilityInvocationFailureCategory.NONE, response.failureCategory());
        assertEquals(Map.of("orderStatus", "PAID"), response.data());
        assertEquals(Map.of("statusCode", 200), response.safeMetadata());
    }

    @Test
    void classifiesBusinessFailureAndExpiredDeadlineWithoutInvokingTransport() {
        CapabilityInvocationAssetResolver resolver = mock(CapabilityInvocationAssetResolver.class);
        CapabilityInvocationAsset asset = asset("CATALOG_HTTP", true);
        when(resolver.require("orders:queryOrder")).thenReturn(asset);
        CapabilityInvoker invoker = mock(CapabilityInvoker.class);
        when(invoker.supports(asset)).thenReturn(true);
        when(invoker.invoke(any(), any())).thenReturn(Map.of(
                "success", false,
                "code", "CAPABILITY_BUSINESS_RESPONSE_FAILED",
                "message", "order rejected",
                "businessCode", "ORDER_REJECTED"));
        CapabilityInvocationApplicationService service = service(resolver, invoker);

        CapabilityInvocationResponse business = service.invoke(request("inv-business", null));
        assertEquals(CapabilityInvocationStatus.BUSINESS_FAILED, business.status());
        assertEquals(CapabilityInvocationFailureCategory.BUSINESS_RESPONSE, business.failureCategory());

        CapabilityInvocationResponse expired = service.invoke(
                request("inv-expired", System.currentTimeMillis() - 1));
        assertEquals(CapabilityInvocationStatus.TECHNICAL_FAILED, expired.status());
        assertEquals(CapabilityInvocationFailureCategory.TIMEOUT, expired.failureCategory());
        assertEquals(false, expired.retryable());
        verify(invoker, org.mockito.Mockito.times(1)).invoke(any(), any());
    }

    @Test
    void exposesRetryableConnectivityButFailsClosedForUnknownInvoker() {
        CapabilityInvocationAssetResolver resolver = mock(CapabilityInvocationAssetResolver.class);
        CapabilityInvocationAsset asset = asset("CATALOG_HTTP", true);
        when(resolver.require("orders:queryOrder")).thenReturn(asset);
        CapabilityInvoker failing = mock(CapabilityInvoker.class);
        when(failing.supports(asset)).thenReturn(true);
        when(failing.invoke(any(), any())).thenThrow(new RuntimeException(new ConnectException("refused")));
        CapabilityInvocationResponse connectivity = service(resolver, failing)
                .invoke(request("inv-connect", null));
        assertEquals(CapabilityInvocationStatus.TECHNICAL_FAILED, connectivity.status());
        assertEquals(CapabilityInvocationFailureCategory.CONNECTIVITY, connectivity.failureCategory());
        assertEquals(true, connectivity.retryable());

        CapabilityInvocationResponse unsupported = new CapabilityInvocationApplicationService(
                resolver, new CapabilityInvocationPolicyChain(), new CapabilityInvokerRegistry(List.of()))
                .invoke(request("inv-unsupported", null));
        assertEquals(CapabilityInvocationStatus.REJECTED, unsupported.status());
        assertEquals(CapabilityInvocationFailureCategory.CONFIGURATION_INVALID,
                unsupported.failureCategory());
        assertNull(unsupported.data());
    }

    @Test
    void builtInEchoUsesKernelAssetWithoutExternalHttp() {
        CapabilityInvocationAssetResolver resolver = mock(CapabilityInvocationAssetResolver.class);
        CapabilityInvocationAsset asset = new CapabilityInvocationAsset(
                CapabilityInvocationAsset.Source.KERNEL_ASSET,
                "orders:queryOrder", "echo", "Echo", "orders", "ECHO", "READ", true);
        when(resolver.require("orders:queryOrder")).thenReturn(asset);
        CapabilityInvocationApplicationService service = service(
                resolver, new BuiltinEchoCapabilityInvoker());

        CapabilityInvocationResponse response = service.invoke(request("inv-echo", null));

        assertEquals(CapabilityInvocationStatus.SUCCEEDED, response.status());
        assertEquals(Map.of("orderNo", "A001"), response.data());
        assertEquals(Map.of("transport", "BUILTIN"), response.safeMetadata());
    }

    @Test
    void classifiesDeadlineExpiryBetweenPolicyAndNetworkAsNonRetryableTimeout() {
        CapabilityInvocationAssetResolver resolver = mock(CapabilityInvocationAssetResolver.class);
        CapabilityInvocationAsset asset = asset("CATALOG_HTTP", true);
        when(resolver.require("orders:queryOrder")).thenReturn(asset);
        CapabilityInvoker invoker = mock(CapabilityInvoker.class);
        when(invoker.supports(asset)).thenReturn(true);
        when(invoker.invoke(any(), any()))
                .thenThrow(new IllegalStateException("Capability invocation deadline has expired"));

        CapabilityInvocationResponse response = service(resolver, invoker)
                .invoke(request("inv-deadline-race", System.currentTimeMillis() + 30_000));

        assertEquals(CapabilityInvocationStatus.TECHNICAL_FAILED, response.status());
        assertEquals(CapabilityInvocationFailureCategory.TIMEOUT, response.failureCategory());
        assertEquals(false, response.retryable());
        assertEquals("CAPABILITY_DEADLINE_EXCEEDED", response.code());
    }

    private CapabilityInvocationApplicationService service(CapabilityInvocationAssetResolver resolver,
                                                           CapabilityInvoker... invokers) {
        return new CapabilityInvocationApplicationService(
                resolver,
                new CapabilityInvocationPolicyChain(),
                new CapabilityInvokerRegistry(List.of(invokers)));
    }

    private CapabilityInvocationAsset asset(String executorType, boolean enabled) {
        return new CapabilityInvocationAsset(
                CapabilityInvocationAsset.Source.TOOL_CATALOG,
                "orders:queryOrder", "queryOrder", "Query order", "orders",
                executorType, "READ_ONLY", enabled);
    }

    private CapabilityInvocationRequest request(String invocationId, Long deadline) {
        return new CapabilityInvocationRequest(
                1, invocationId, "orders:queryOrder", Map.of("orderNo", "A001"),
                Map.of(), Map.of(), Map.of(), deadline, "idem-1", Map.of("traceId", "trace-1"));
    }
}
