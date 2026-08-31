package com.enterprise.ai.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class BizExceptionTest {

    @Test
    void messageConstructorUsesDefault500AndNoCause() {
        BizException ex = new BizException("boom");

        assertEquals(500, ex.getCode());
        assertEquals("boom", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void codeMessageConstructorPreservesCustomCodeAndNoCause() {
        BizException ex = new BizException(400, "bad request");

        assertEquals(400, ex.getCode());
        assertEquals("bad request", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void causeConstructorPreservesExactCause() {
        IllegalStateException cause = new IllegalStateException("root");
        BizException ex = new BizException(500, "wrapped", cause);

        assertEquals(500, ex.getCode());
        assertEquals("wrapped", ex.getMessage());
        assertSame(cause, ex.getCause());
    }
}
