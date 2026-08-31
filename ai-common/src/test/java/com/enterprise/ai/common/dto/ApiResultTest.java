package com.enterprise.ai.common.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ApiResultTest {

    @Test
    void okWithDataUses200SuccessAndSamePayload() {
        Object payload = new Object();
        ApiResult<Object> result = ApiResult.ok(payload);

        assertEquals(200, result.getCode());
        assertEquals("success", result.getMessage());
        assertSame(payload, result.getData());
    }

    @Test
    void okWithoutDataUses200SuccessAndNullData() {
        ApiResult<String> result = ApiResult.ok();

        assertEquals(200, result.getCode());
        assertEquals("success", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void failMessageUses500AndNullData() {
        ApiResult<String> result = ApiResult.fail("oops");

        assertEquals(500, result.getCode());
        assertEquals("oops", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void failCustomCodePreservesCodeMessageAndNullData() {
        ApiResult<String> result = ApiResult.fail(404, "not found");

        assertEquals(404, result.getCode());
        assertEquals("not found", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void noArgBeanSupportsIndependentPropertyRoundTrip() {
        ApiResult<String> result = new ApiResult<>();
        assertEquals(0, result.getCode());
        assertNull(result.getMessage());
        assertNull(result.getData());

        result.setCode(201);
        result.setMessage("created");
        result.setData("payload");

        assertEquals(201, result.getCode());
        assertEquals("created", result.getMessage());
        assertEquals("payload", result.getData());
    }
}
