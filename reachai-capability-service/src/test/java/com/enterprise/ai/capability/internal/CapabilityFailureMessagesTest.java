package com.enterprise.ai.capability.internal;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityFailureMessagesTest {

    @Test
    void connectionRefusedNamesTheBusinessSystemOriginButNotThePath() {
        ResourceAccessException failure = new ResourceAccessException(
                "I/O error on POST request for \"http://erp.example.com:48080/admin-api/crm/customer/create?token=x\": Connection refused",
                new ConnectException("Connection refused"));

        String message = CapabilityFailureMessages.technicalFailure(
                failure, CapabilityInvocationFailureCategory.CONNECTIVITY);

        assertTrue(message.startsWith("连不上业务系统（http://erp.example.com:48080）"), message);
        assertTrue(message.contains("访问地址"), message);
        assertFalse(message.contains("customer/create"), message);
        assertFalse(message.contains("token"), message);
    }

    @Test
    void timeoutAndUnknownHostGetTheirOwnNextStep() {
        String timeout = CapabilityFailureMessages.technicalFailure(
                new ResourceAccessException("I/O error on GET request for \"https://erp.example.com/a\": Read timed out",
                        new SocketTimeoutException("Read timed out")),
                CapabilityInvocationFailureCategory.TIMEOUT);
        assertTrue(timeout.startsWith("业务系统（https://erp.example.com）在规定时间内没有响应"), timeout);

        String unknownHost = CapabilityFailureMessages.technicalFailure(
                new ResourceAccessException("I/O error", new UnknownHostException("erp.invalid")),
                CapabilityInvocationFailureCategory.CONNECTIVITY);
        assertTrue(unknownHost.startsWith("找不到业务系统"), unknownHost);
    }

    @Test
    void httpStatusFailuresNeverEchoTheUpstreamBody() {
        HttpClientErrorException unauthorized = HttpClientErrorException.create(HttpStatus.UNAUTHORIZED,
                "Unauthorized", null, "{\"secret\":\"upstream-token\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        String message = CapabilityFailureMessages.technicalFailure(
                unauthorized, CapabilityInvocationFailureCategory.INTERNAL);
        assertTrue(message.startsWith("业务系统拒绝了这次调用（HTTP 401）"), message);
        assertFalse(message.contains("upstream-token"), message);

        String server = CapabilityFailureMessages.technicalFailure(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", null, null, null),
                CapabilityInvocationFailureCategory.INTERNAL);
        assertEquals("业务系统内部出错（HTTP 502）。请稍后重试；一直失败请联系业务系统负责人查看日志。", server);
        assertTrue(CapabilityFailureMessages.httpFailure(404).contains("重新同步接口"));
    }
}
