package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.governance.ControlA2aEndpointController;
import com.enterprise.ai.control.governance.ControlMcpEndpointController;
import com.enterprise.ai.control.platform.PlatformEmbedPublicController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformControlRetiredSurfaceTest {

    @Test
    void retiredPlatformControlProxyControllerIsNotOnClasspath() {
        assertThrows(ClassNotFoundException.class, () ->
                Class.forName("com.enterprise.ai.control.compat.PlatformControlCompatibilityProxyController"));
    }

    @Test
    void keepsExternalPlatformControlContractsOnRealControllers() {
        assertHasRoute(ControlMcpEndpointController.class, "/mcp/manifest");
        assertHasRoute(ControlMcpEndpointController.class, "/mcp/jsonrpc");
        assertHasRoute(ControlA2aEndpointController.class, "/a2a/{agentKey}/.well-known/agent.json");
        assertHasRoute(ControlA2aEndpointController.class, "/a2a/{agentKey}/jsonrpc");
        assertHasRoute(RuntimeCompatibilityController.class, "/gateway/catalog");
        assertHasRoute(RuntimeCompatibilityController.class, "/gateway/agents/{key}/chat");
        assertHasRoute(PlatformEmbedPublicController.class, "/api/embed/token/exchange");
        assertHasRoute(PlatformEmbedPublicController.class, "/api/embed/chat/sessions");
        assertHasRoute(PlatformEmbedPublicController.class, "/api/embed/chat/sessions/{sessionId}/messages");
        assertHasRoute(PlatformEmbedPublicController.class, "/api/embed/chat/sessions/{sessionId}/messages/stream");
        assertHasRoute(PlatformEmbedPublicController.class, "/api/embed/chat/sessions/{sessionId}/page-actions/pending");
        assertHasRoute(PlatformEmbedPublicController.class, "/api/embed/chat/sessions/{sessionId}/page-actions/{requestId}/result");
    }

    private static void assertHasRoute(Class<?> controllerType, String path) {
        assertTrue(routePaths(controllerType).contains(path),
                () -> controllerType.getSimpleName() + " should own " + path);
    }

    private static Set<String> routePaths(Class<?> controllerType) {
        String[] prefixes = mappingPaths(controllerType.getAnnotation(RequestMapping.class));
        if (prefixes.length == 0) {
            prefixes = new String[] {""};
        }
        Set<String> result = new LinkedHashSet<>();
        for (Method method : controllerType.getDeclaredMethods()) {
            for (String methodPath : methodPaths(method)) {
                for (String prefix : prefixes) {
                    result.add(join(prefix, methodPath));
                }
            }
        }
        return result;
    }

    private static Set<String> methodPaths(Method method) {
        Set<String> paths = new LinkedHashSet<>();
        addAll(paths, mappingPaths(method.getAnnotation(RequestMapping.class)));
        addAll(paths, mappingPaths(method.getAnnotation(GetMapping.class)));
        addAll(paths, mappingPaths(method.getAnnotation(PostMapping.class)));
        addAll(paths, mappingPaths(method.getAnnotation(PutMapping.class)));
        addAll(paths, mappingPaths(method.getAnnotation(DeleteMapping.class)));
        return paths;
    }

    private static String[] mappingPaths(RequestMapping mapping) {
        if (mapping == null) {
            return new String[0];
        }
        return firstNonEmpty(mapping.path(), mapping.value());
    }

    private static String[] mappingPaths(GetMapping mapping) {
        if (mapping == null) {
            return new String[0];
        }
        return firstNonEmpty(mapping.path(), mapping.value());
    }

    private static String[] mappingPaths(PostMapping mapping) {
        if (mapping == null) {
            return new String[0];
        }
        return firstNonEmpty(mapping.path(), mapping.value());
    }

    private static String[] mappingPaths(PutMapping mapping) {
        if (mapping == null) {
            return new String[0];
        }
        return firstNonEmpty(mapping.path(), mapping.value());
    }

    private static String[] mappingPaths(DeleteMapping mapping) {
        if (mapping == null) {
            return new String[0];
        }
        return firstNonEmpty(mapping.path(), mapping.value());
    }

    private static String[] firstNonEmpty(String[] path, String[] value) {
        return path.length == 0 ? value : path;
    }

    private static void addAll(Set<String> target, String[] paths) {
        target.addAll(Arrays.asList(paths));
    }

    private static String join(String prefix, String path) {
        if (prefix == null || prefix.isBlank()) {
            return path;
        }
        if (path == null || path.isBlank()) {
            return prefix;
        }
        return prefix.endsWith("/") || path.startsWith("/") ? prefix + path : prefix + "/" + path;
    }
}
