package com.enterprise.ai.reach.spring;

import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Many business systems answer HTTP 200 with {@code {"code": 1002, "msg": "客户名称不能为空"}}. This advice reads the
 * already-built response object (no serialization, no change to the body) and marks such answers as failures.
 */
@ControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ReachAiActivityResponseAdvice implements ResponseBodyAdvice<Object> {

    static final String BUSINESS_ERROR = ReachAiActivityResponseAdvice.class.getName() + ".BUSINESS_ERROR";

    private final List<Integer> successCodes;

    public ReachAiActivityResponseAdvice(ReachAiRegistryProperties properties) {
        this.successCodes = properties.getActivity().getSuccessCodes();
    }

    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class selectedConverterType, ServerHttpRequest request, ServerHttpResponse response) {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes != null && attributes.getAttribute(ReachAiActivityInterceptor.START,
                    RequestAttributes.SCOPE_REQUEST) != null) {
                String failure = businessFailure(body, successCodes);
                if (failure != null) attributes.setAttribute(BUSINESS_ERROR, failure, RequestAttributes.SCOPE_REQUEST);
            }
        } catch (Throwable ignored) {
            // recording must never change what the business system answers
        }
        return body;
    }

    static String businessFailure(Object body, List<Integer> successCodes) {
        if (body == null || body instanceof CharSequence || body instanceof byte[]
                || body.getClass().getName().startsWith("org.springframework.core.io.")) {
            return null;
        }
        Object code;
        Object success;
        Object message;
        if (body instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) body;
            code = map.get("code");
            success = map.get("success");
            message = map.containsKey("msg") ? map.get("msg") : map.get("message");
        } else {
            code = property(body, "getCode");
            success = property(body, "getSuccess");
            if (success == null) success = property(body, "isSuccess");
            message = property(body, "getMsg");
            if (message == null) message = property(body, "getMessage");
        }
        boolean failed = false;
        if (Boolean.FALSE.equals(success)) failed = true;
        Integer numeric = number(code);
        if (numeric != null && !successCodes.contains(numeric)) failed = true;
        if (!failed) return null;
        if (message instanceof String && !((String) message).trim().isEmpty()) return ((String) message).trim();
        return numeric == null ? "业务处理失败" : "业务返回码 " + numeric;
    }

    private static Integer number(Object code) {
        if (code instanceof Number) return ((Number) code).intValue();
        if (code instanceof String && ((String) code).trim().matches("-?\\d{1,9}")) {
            return Integer.valueOf(((String) code).trim());
        }
        return null;
    }

    private static Object property(Object target, String getter) {
        try {
            Method method = target.getClass().getMethod(getter);
            if (method.getParameterCount() != 0) return null;
            return method.invoke(target);
        } catch (Exception missing) {
            return null;
        }
    }
}
