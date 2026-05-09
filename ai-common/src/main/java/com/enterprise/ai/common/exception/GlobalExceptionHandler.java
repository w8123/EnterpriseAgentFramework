package com.enterprise.ai.common.exception;

import com.enterprise.ai.common.dto.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ApiResult<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return ApiResult.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResult<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse("参数校验失败");
        return ApiResult.fail(400, msg);
    }

    @ExceptionHandler(BindException.class)
    public ApiResult<Void> handleBind(BindException e) {
        String msg = e.getAllErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse("参数绑定失败");
        return ApiResult.fail(400, msg);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResult<Void> handleIllegalArgument(IllegalArgumentException e) {
        return ApiResult.fail(400, e.getMessage());
    }

    /**
     * Spring 6：未匹配任何 Controller 的请求会落到静态资源处理器；常见于客户端 URL 打错服务（如误用 agent 地址调业务 API）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ApiResult<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("未匹配路径: method={}, resourcePath={}", e.getHttpMethod(), e.getResourcePath());
        return ApiResult.fail(404, "资源不存在: " + e.getResourcePath());
    }

    /**
     * 客户端在响应尚未写完时关闭连接（取消请求、浏览器离开页面、Feign/网关超时等）。
     * 返回 void，避免再向已断开的 Socket 写入错误体。
     */
    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException e) {
        log.warn("客户端中断连接: {}", e.getMessage());
    }

    /**
     * 异步写出过程中连接失效；常见根因仍是 {@link ClientAbortException} / 连接被对端重置。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException e) {
        if (isLikelyClientDisconnect(e)) {
            log.warn("客户端中断连接（异步响应写出）: {}", e.getMessage());
        } else {
            log.warn("异步响应不可用: {}", e.getMessage(), e);
        }
    }

    private static boolean isLikelyClientDisconnect(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof ClientAbortException) {
                return true;
            }
            if (t instanceof IOException) {
                String m = t.getMessage();
                if (m != null && (m.contains("Connection reset")
                        || m.contains("Broken pipe")
                        || m.contains("中止了一个已建立的连接"))) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    @ExceptionHandler(Exception.class)
    public ApiResult<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return ApiResult.fail(500, "系统内部错误: " + e.getMessage());
    }
}
