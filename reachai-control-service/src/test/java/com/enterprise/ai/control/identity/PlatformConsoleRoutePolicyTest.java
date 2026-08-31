package com.enterprise.ai.control.identity;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformConsoleRoutePolicyTest {

    private final AntPathMatcher matcher = new AntPathMatcher();

    @Test
    void coversConsoleWorkflowProjectAndCapabilityOperations() {
        assertProtected("/api/workflows/wf_1/working-copy");
        assertProtected("/api/agents/agent_1/config-versions");
        assertProtected("/api/skills/11/versions/21/reviews");
        assertProtected("/api/skill-market/probes");
        assertProtected("/api/scan-projects/7/tools/reconcile");
        assertProtected("/api/api-market/entries/open-meteo");
        assertProtected("/api/tools/orders.create/test");
        assertProtected("/api/registry/projects/mall/page-workbench/pages");
        assertProtected("/api/ai-assist/projects/7/onboarding-manifest");
        assertProtected("/api/runtime/debug-sessions/debug_1");
        assertProtected("/api/runtime/agents/sessions/session_1");
        assertProtected("/api/runtime/evals/v2/experiments");
        assertProtected("/api/automations/aut_0123456789abcdef0123456789abcdef/occurrences");
        assertProtected("/api/a2a-hub/overview");
        assertProtected("/api/knowledge/biz-index/orders/search");
    }

    @Test
    void keepsIndependentProtocolCredentialsOutsideConsoleSessionPolicy() {
        assertNotProtected("/api/platform/auth/login");
        assertNotProtected("/api/embed/chat/sessions");
        assertNotProtected("/api/registry/projects/mall/capabilities/sync");
        assertNotProtected("/api/ai-coding/projects/7/manifest");
        assertNotProtected("/api/ai-coding/tasks/task_1/events");
        assertNotProtected("/api/workflows/ai-coding/workflows");
        assertNotProtected("/api/workflows/wf_1/ai-coding/context");
        assertNotProtected("/api/runtime/agents/execute");
        assertNotProtected("/api/v1/agents/demo/chat");
        assertNotProtected("/api/ai-assist/artifacts/embed-chat/1.0.0.tgz");
        assertNotProtected("/api/ai-assist/skills/reachai-onboarding/latest.zip");
        assertNotProtected("/api/knowledge-ingress/projects/orders/biz-index/orders_idx/batch");
        assertNotProtected("/.well-known/agent-card.json");
    }

    @Test
    void keepsRegistryCompatibilityExplicitlyPendingWhileProtectingItsConsoleWorkbench() {
        assertTrue(PlatformConsoleRoutePolicy.credentialDomainFor(
                        "/api/registry/projects/mall/capabilities/sync")
                        == PlatformConsoleRoutePolicy.CredentialDomain.COMPATIBILITY_PENDING);
        assertTrue(PlatformConsoleRoutePolicy.credentialDomainFor(
                        "/api/registry/projects/mall/page-workbench/pages")
                        == PlatformConsoleRoutePolicy.CredentialDomain.PLATFORM_SESSION);
        assertTrue(PlatformConsoleRoutePolicy.credentialDomainFor(
                        "/api/workflows/wf_1/ai-coding/context")
                        == PlatformConsoleRoutePolicy.CredentialDomain.INDEPENDENT_PROTOCOL);
    }

    @Test
    void classifiesEveryMappedControlControllerEndpoint() throws IOException, URISyntaxException {
        Set<String> unclassified = new LinkedHashSet<>();
        for (Class<?> controller : controlControllers()) {
            List<String> classPaths = mappingPaths(controller);
            if (classPaths.isEmpty()) {
                classPaths = List.of("");
            }
            for (Method method : controller.getDeclaredMethods()) {
                List<String> methodPaths = mappingPaths(method);
                if (methodPaths.isEmpty()) {
                    continue;
                }
                for (String classPath : classPaths) {
                    for (String methodPath : methodPaths) {
                        String path = joinPath(classPath, methodPath);
                        if (PlatformConsoleRoutePolicy.credentialDomainFor(path)
                                == PlatformConsoleRoutePolicy.CredentialDomain.UNCLASSIFIED) {
                            unclassified.add(controller.getSimpleName() + "#" + method.getName() + ": " + path);
                        }
                    }
                }
            }
        }
        assertTrue(unclassified.isEmpty(), () -> "Unclassified Control routes: " + unclassified);
    }

    private void assertProtected(String path) {
        assertTrue(PlatformConsoleRoutePolicy.PROTECTED_PATH_PATTERNS.stream()
                .anyMatch(pattern -> matcher.match(pattern, path)), path);
    }

    private void assertNotProtected(String path) {
        boolean protectedPath = PlatformConsoleRoutePolicy.PROTECTED_PATH_PATTERNS.stream()
                .anyMatch(pattern -> matcher.match(pattern, path));
        boolean excludedPath = PlatformConsoleRoutePolicy.EXCLUDED_PATH_PATTERNS.stream()
                .anyMatch(pattern -> matcher.match(pattern, path));
        boolean independentPath = PlatformConsoleRoutePolicy.INDEPENDENT_PROTOCOL_PATH_PATTERNS.stream()
                .anyMatch(pattern -> matcher.match(pattern, path));
        assertFalse(protectedPath && !excludedPath && !independentPath, path);
    }

    private static List<Class<?>> controlControllers() throws IOException, URISyntaxException {
        Path classesRoot = Path.of(PlatformConsoleRoutePolicy.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path controlPackage = classesRoot.resolve("com/enterprise/ai/control");
        try (Stream<Path> files = Files.walk(controlPackage)) {
            return files
                    .filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().contains("$"))
                    .map(path -> className(classesRoot, path))
                    .map(PlatformConsoleRoutePolicyTest::loadClass)
                    .filter(PlatformConsoleRoutePolicyTest::isController)
                    .toList();
        }
    }

    private static String className(Path classesRoot, Path classFile) {
        String relative = classesRoot.relativize(classFile).toString();
        return relative.substring(0, relative.length() - ".class".length())
                .replace('\\', '.')
                .replace('/', '.');
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className, false, PlatformConsoleRoutePolicyTest.class.getClassLoader());
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("Cannot inspect Control class " + className, error);
        }
    }

    private static boolean isController(Class<?> type) {
        return AnnotatedElementUtils.hasAnnotation(type, RestController.class)
                || AnnotatedElementUtils.hasAnnotation(type, Controller.class);
    }

    private static List<String> mappingPaths(AnnotatedElement element) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(element, RequestMapping.class);
        if (mapping == null) {
            return List.of();
        }
        String[] values = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return values.length == 0 ? List.of("") : Arrays.asList(values);
    }

    private static String joinPath(String classPath, String methodPath) {
        String parent = classPath == null ? "" : classPath;
        String child = methodPath == null ? "" : methodPath;
        if (parent.isEmpty()) {
            return child.isEmpty() ? "/" : normalizePath(child);
        }
        if (child.isEmpty()) {
            return normalizePath(parent);
        }
        return normalizePath(parent + "/" + child);
    }

    private static String normalizePath(String value) {
        String normalized = value.startsWith("/") ? value : "/" + value;
        return normalized.replaceAll("/{2,}", "/");
    }
}
