package com.enterprise.ai.common.exception;

import com.enterprise.ai.common.dto.ApiResult;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerTest {

    @Test
    void preservesExplicitHttpStatusInsteadOfReturningInternalError() {
        ResponseEntity<ApiResult<Void>> response = new GlobalExceptionHandler()
                .handleResponseStatus(new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "platform login is required"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(401, response.getBody().getCode());
        assertEquals("platform login is required", response.getBody().getMessage());
    }

    @Test
    void mapsBizExceptionCodeMessageAndNullData() {
        ApiResult<Void> result = new GlobalExceptionHandler()
                .handleBizException(new BizException(422, "invalid tenant"));

        assertEquals(422, result.getCode());
        assertEquals("invalid tenant", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void usesFirstMethodArgumentValidationError() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.reject("name", "name is required");
        bindingResult.reject("age", "age must be positive");

        ApiResult<Void> result = new GlobalExceptionHandler()
                .handleValidation(new MethodArgumentNotValidException(null, bindingResult));

        assertEquals(400, result.getCode());
        assertEquals("name is required", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void usesValidationFallbackWhenNoErrors() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");

        ApiResult<Void> result = new GlobalExceptionHandler()
                .handleValidation(new MethodArgumentNotValidException(null, bindingResult));

        assertEquals(400, result.getCode());
        assertEquals("参数校验失败", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void usesFirstBindError() {
        BindException bindException = new BindException(new Object(), "request");
        bindException.reject("field", "field is invalid");
        bindException.reject("other", "other is invalid");

        ApiResult<Void> result = new GlobalExceptionHandler().handleBind(bindException);

        assertEquals(400, result.getCode());
        assertEquals("field is invalid", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void usesBindFallbackWhenNoErrors() {
        BindException bindException = new BindException(new Object(), "request");

        ApiResult<Void> result = new GlobalExceptionHandler().handleBind(bindException);

        assertEquals(400, result.getCode());
        assertEquals("参数绑定失败", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void mapsIllegalArgumentTo400() {
        ApiResult<Void> result = new GlobalExceptionHandler()
                .handleIllegalArgument(new IllegalArgumentException("bad input"));

        assertEquals(400, result.getCode());
        assertEquals("bad input", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void fallsBackToStatusTextWhenResponseStatusReasonIsNull() {
        ResponseEntity<ApiResult<Void>> response = new GlobalExceptionHandler()
                .handleResponseStatus(new ResponseStatusException(HttpStatus.BAD_GATEWAY));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(502, response.getBody().getCode());
        assertEquals("502 BAD_GATEWAY", response.getBody().getMessage());
        assertNull(response.getBody().getData());
    }

    @Test
    void fallsBackToStatusTextWhenResponseStatusReasonIsBlank() {
        ResponseEntity<ApiResult<Void>> response = new GlobalExceptionHandler()
                .handleResponseStatus(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "   "));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(503, response.getBody().getCode());
        assertEquals("503 SERVICE_UNAVAILABLE", response.getBody().getMessage());
        assertNull(response.getBody().getData());
    }

    @Test
    void mapsMissingResourceTo404WithResourcePath() {
        ApiResult<Void> result = new GlobalExceptionHandler()
                .handleNoResourceFound(new NoResourceFoundException(HttpMethod.GET, "/api/unknown"));

        assertEquals(404, result.getCode());
        assertEquals("资源不存在: /api/unknown", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void mapsGenericExceptionTo500WithPrefixedMessage() {
        ApiResult<Void> result = new GlobalExceptionHandler()
                .handleException(new Exception("boom"));

        assertEquals(500, result.getCode());
        assertEquals("系统内部错误: boom", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void declaresAdviceAndExpectedExceptionHandlerMappings() {
        assertNotNull(GlobalExceptionHandler.class.getAnnotation(RestControllerAdvice.class));

        Map<String, Class<?>[]> actual = new LinkedHashMap<>();
        for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
            ExceptionHandler handler = method.getAnnotation(ExceptionHandler.class);
            if (handler != null) {
                actual.put(method.getName(), handler.value());
            }
        }

        Map<String, Class<?>[]> expected = new LinkedHashMap<>();
        expected.put("handleBizException", new Class<?>[]{BizException.class});
        expected.put("handleValidation", new Class<?>[]{MethodArgumentNotValidException.class});
        expected.put("handleBind", new Class<?>[]{BindException.class});
        expected.put("handleIllegalArgument", new Class<?>[]{IllegalArgumentException.class});
        expected.put("handleResponseStatus", new Class<?>[]{ResponseStatusException.class});
        expected.put("handleNoResourceFound", new Class<?>[]{NoResourceFoundException.class});
        expected.put("handleClientAbort", new Class<?>[]{ClientAbortException.class});
        expected.put("handleAsyncRequestNotUsable", new Class<?>[]{AsyncRequestNotUsableException.class});
        expected.put("handleException", new Class<?>[]{Exception.class});

        assertEquals(expected.keySet(), actual.keySet());
        for (Map.Entry<String, Class<?>[]> entry : expected.entrySet()) {
            assertArrayEquals(entry.getValue(), actual.get(entry.getKey()), entry.getKey());
        }
    }
}
