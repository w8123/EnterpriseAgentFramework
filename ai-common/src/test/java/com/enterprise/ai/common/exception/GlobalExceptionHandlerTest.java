package com.enterprise.ai.common.exception;

import com.enterprise.ai.common.dto.ApiResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
