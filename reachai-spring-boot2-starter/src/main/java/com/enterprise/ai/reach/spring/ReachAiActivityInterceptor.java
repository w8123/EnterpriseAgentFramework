package com.enterprise.ai.reach.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ui.ModelMap;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.WebRequestInterceptor;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Records one event per handled request of a logged-in employee: which operation (method + path template), on which
 * page, with which search word, and whether it failed. Uses only Spring abstractions so it runs on Boot 2 and Boot 3.
 */
public class ReachAiActivityInterceptor implements WebRequestInterceptor {

    static final String START = ReachAiActivityInterceptor.class.getName() + ".START";
    static final String BEST_MATCHING_HANDLER = "org.springframework.web.servlet.HandlerMapping.bestMatchingHandler";
    static final String BEST_MATCHING_PATTERN = "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";
    static final String RESOLVED_EXCEPTION = "org.springframework.web.servlet.DispatcherServlet.EXCEPTION";
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d{15,}[\\dXx]?(?!\\d)");

    private final ReachAiRegistryProperties.Activity settings;
    private final ReachAiActivityRecorder recorder;
    private final ObjectProvider<ReachAiCurrentUserProvider> currentUsers;
    private final ObjectProvider<ReachAiEmbedPrincipalResolver> embedPrincipals;
    private final AntPathMatcher matcher = new AntPathMatcher();
    private final Map<Method, String> names = new ConcurrentHashMap<Method, String>();

    public ReachAiActivityInterceptor(ReachAiRegistryProperties properties,
                                      ReachAiActivityRecorder recorder,
                                      ObjectProvider<ReachAiCurrentUserProvider> currentUsers,
                                      ObjectProvider<ReachAiEmbedPrincipalResolver> embedPrincipals) {
        this.settings = properties.getActivity();
        this.recorder = recorder;
        this.currentUsers = currentUsers;
        this.embedPrincipals = embedPrincipals;
    }

    @Override
    public void preHandle(WebRequest request) {
        if (request.getAttribute(START, RequestAttributes.SCOPE_REQUEST) == null) {
            request.setAttribute(START, System.nanoTime(), RequestAttributes.SCOPE_REQUEST);
        }
    }

    @Override
    public void postHandle(WebRequest request, ModelMap model) {
        // nothing to do before the view renders
    }

    @Override
    public void afterCompletion(WebRequest request, Exception ex) {
        try {
            Map<String, Object> event = event(request, ex);
            if (event != null) recorder.record(event);
        } catch (Throwable ignored) {
            // recording must never affect the business request
        }
    }

    Map<String, Object> event(WebRequest request, Exception ex) {
        Object start = request.getAttribute(START, RequestAttributes.SCOPE_REQUEST);
        if (!(start instanceof Long) || ReachAiInvocationContextHolder.get() != null) return null;
        Object handler = request.getAttribute(BEST_MATCHING_HANDLER, RequestAttributes.SCOPE_REQUEST);
        if (!(handler instanceof HandlerMethod)) return null;
        Object patternValue = request.getAttribute(BEST_MATCHING_PATTERN, RequestAttributes.SCOPE_REQUEST);
        String pattern = patternValue == null ? null : String.valueOf(patternValue);
        if (!StringUtils.hasText(pattern) || excluded(pattern)) return null;
        String referer = request.getHeader("Referer");
        if (settings.isBrowserRequestsOnly() && !StringUtils.hasText(referer)
                && !StringUtils.hasText(request.getHeader("Origin"))) {
            return null;
        }
        User user = user(request);
        if (user == null) return null;
        String httpMethod = nativeString(request, true, "getMethod");
        if (!StringUtils.hasText(httpMethod)) httpMethod = "GET";
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("type", "OPERATION");
        event.put("operation", httpMethod.toUpperCase(java.util.Locale.ROOT) + " " + pattern);
        String name = operationName(((HandlerMethod) handler).getMethod());
        if (name != null) event.put("operationName", name);
        String page = pagePath(referer);
        if (page != null) event.put("page", page);
        if ("GET".equalsIgnoreCase(httpMethod)) {
            String keyword = keyword(request);
            if (keyword != null) {
                event.put("keyword", keyword);
                event.put("type", "SEARCH");
            }
        }
        String failure = failure(request, ex);
        event.put("outcome", failure == null ? "SUCCESS" : "FAILED");
        if (failure != null) event.put("error", mask(truncate(failure, 200)));
        event.put("durationMs", (System.nanoTime() - (Long) start) / 1_000_000L);
        event.put("userId", user.id);
        if (user.name != null) event.put("userName", user.name);
        if (user.tenant != null) event.put("tenantId", user.tenant);
        event.put("occurredAt", System.currentTimeMillis());
        return event;
    }

    private boolean excluded(String pattern) {
        for (String exclude : settings.getExcludePaths()) {
            if (StringUtils.hasText(exclude) && (matcher.match(exclude, pattern) || pattern.startsWith("/reachai/"))) return true;
        }
        return pattern.startsWith("/reachai/");
    }

    private String failure(WebRequest request, Exception ex) {
        Object business = request.getAttribute(ReachAiActivityResponseAdvice.BUSINESS_ERROR, RequestAttributes.SCOPE_REQUEST);
        if (business instanceof String) return (String) business;
        Throwable error = ex;
        if (error == null) {
            Object resolved = request.getAttribute(RESOLVED_EXCEPTION, RequestAttributes.SCOPE_REQUEST);
            if (resolved instanceof Throwable) error = (Throwable) resolved;
        }
        if (error != null) {
            String message = error.getMessage();
            return StringUtils.hasText(message) ? message : error.getClass().getSimpleName();
        }
        String status = nativeString(request, false, "getStatus");
        if (status != null && status.matches("\\d{3}") && Integer.parseInt(status) >= 400) return "HTTP " + status;
        return null;
    }

    private String keyword(WebRequest request) {
        for (String parameter : settings.getSearchParameters()) {
            if (!StringUtils.hasText(parameter)) continue;
            String value = request.getParameter(parameter);
            if (StringUtils.hasText(value)) return mask(truncate(value.trim(), 64));
        }
        return null;
    }

    private User user(WebRequest request) {
        ReachAiCurrentUserProvider provider = currentUsers.getIfAvailable();
        if (provider != null) {
            try {
                ReachAiPrincipal principal = provider.currentUser();
                if (principal != null) {
                    String id = firstText(principal.getExternalUserId(), principal.getGlobalUserId());
                    if (id != null) return new User(id, principal.getUserName(), principal.getTenantId());
                }
            } catch (Throwable ignored) {
                // fall through to the next source
            }
        }
        ReachAiEmbedPrincipalResolver resolver = embedPrincipals.getIfAvailable();
        if (resolver != null) {
            try {
                ReachAiEmbedPrincipal principal = resolver.resolve(null);
                if (principal != null) {
                    String id = firstText(principal.getExternalUserId(), principal.getGlobalUserId());
                    Object tenant = principal.getAttributes() == null ? null : principal.getAttributes().get("tenantId");
                    if (id != null) return new User(id, principal.getUserName(), tenant == null ? null : String.valueOf(tenant));
                }
            } catch (Throwable ignored) {
                // fall through to the container user
            }
        }
        String remote = request.getRemoteUser();
        if (StringUtils.hasText(remote)) return new User(remote, null, null);
        Principal principal = request.getUserPrincipal();
        if (principal != null && StringUtils.hasText(principal.getName())) return new User(principal.getName(), null, null);
        return null;
    }

    String operationName(Method method) {
        String cached = names.get(method);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String name = null;
        for (Annotation annotation : method.getAnnotations()) {
            String type = annotation.annotationType().getName();
            if ("io.swagger.v3.oas.annotations.Operation".equals(type)) {
                name = firstText(attribute(annotation, "summary"), attribute(annotation, "description"));
            } else if ("io.swagger.annotations.ApiOperation".equals(type)) {
                name = attribute(annotation, "value");
            } else if ("com.enterprise.ai.reach.sdk.annotation.ReachCapability".equals(type)) {
                name = firstText(attribute(annotation, "name"), attribute(annotation, "description"));
            }
            if (name != null) break;
        }
        name = name == null ? null : truncate(name, 100);
        names.put(method, name == null ? "" : name);
        return name;
    }

    private static String attribute(Annotation annotation, String property) {
        try {
            Object value = annotation.annotationType().getMethod(property).invoke(annotation);
            return value instanceof String && StringUtils.hasText((String) value) ? ((String) value).trim() : null;
        } catch (Exception missing) {
            return null;
        }
    }

    static String pagePath(String referer) {
        if (!StringUtils.hasText(referer)) return null;
        String value = referer.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            int slash = value.indexOf('/', scheme + 3);
            value = slash < 0 ? "/" : value.substring(slash);
        }
        int cut = value.length();
        for (char c : new char[] {'?', '#'}) {
            int index = value.indexOf(c);
            if (index >= 0 && index < cut) cut = index;
        }
        value = value.substring(0, cut);
        return value.isEmpty() ? null : truncate(value, 300);
    }

    /** Reads the servlet request/response through reflection so javax and jakarta both work. */
    private static String nativeString(WebRequest request, boolean fromRequest, String getter) {
        if (!(request instanceof NativeWebRequest)) return null;
        Object target = fromRequest ? ((NativeWebRequest) request).getNativeRequest()
                : ((NativeWebRequest) request).getNativeResponse();
        if (target == null) return null;
        try {
            Object value = target.getClass().getMethod(getter).invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (Exception unavailable) {
            return null;
        }
    }

    static String mask(String value) {
        if (value == null) return null;
        String masked = EMAIL.matcher(value).replaceAll("***@***");
        masked = MOBILE.matcher(masked).replaceAll("$1****$2");
        return LONG_NUMBER.matcher(masked).replaceAll("******");
    }

    private static String truncate(String value, int max) {
        if (value == null || value.codePointCount(0, value.length()) <= max) return value;
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    private static String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    private static final class User {
        final String id;
        final String name;
        final String tenant;

        User(String id, String name, String tenant) {
            this.id = id;
            this.name = StringUtils.hasText(name) ? name.trim() : null;
            this.tenant = StringUtils.hasText(tenant) ? tenant.trim() : null;
        }
    }
}
