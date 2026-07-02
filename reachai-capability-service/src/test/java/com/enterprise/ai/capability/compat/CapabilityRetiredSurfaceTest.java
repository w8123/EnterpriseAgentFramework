package com.enterprise.ai.capability.compat;

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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityRetiredSurfaceTest {

    @Test
    void retiredCapabilityProxyControllerIsNotOnClasspath() {
        assertThrows(ClassNotFoundException.class, () ->
                Class.forName("com.enterprise.ai.capability.compat.CapabilityLegacyCompatibilityProxyController"));
    }

    @Test
    void capabilityMiningRoutesAreOwnedByCapabilityController() {
        Class<?> controller = assertDoesNotThrow(() ->
                Class.forName("com.enterprise.ai.capability.catalog.mining.CapabilityMiningController"));

        for (String base : new String[] {"/api/skill-mining", "/api/capability-mining"}) {
            assertHasRoute(controller, base + "/precheck");
            assertHasRoute(controller, base + "/drafts/generate");
            assertHasRoute(controller, base + "/drafts");
            assertHasRoute(controller, base + "/drafts/{id}/status");
            assertHasRoute(controller, base + "/drafts/{id}/publish");
            assertHasRoute(controller, base + "/drafts/from-trace");
            assertHasRoute(controller, base + "/drafts/from-canvas");
            assertHasRoute(controller, base + "/demo-traces/generate");
            assertHasRoute(controller, base + "/demo-traces/clear");
        }
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
