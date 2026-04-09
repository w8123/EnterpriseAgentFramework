package com.enterprise.ai.common.exception;

import lombok.Getter;

/**
 * 统一业务异常，所有子服务可抛出此异常，由 GlobalExceptionHandler 统一处理。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        super(message);
        this.code = 500;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
