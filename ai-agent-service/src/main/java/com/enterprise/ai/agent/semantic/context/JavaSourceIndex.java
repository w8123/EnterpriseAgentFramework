package com.enterprise.ai.agent.semantic.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 在扫描根路径下构建一份「简单类名 -> .java 源文件路径」的索引，用于 Controller -> Service / Mapper / DTO 追溯。
 * 只做一次遍历，惰性缓存在每次 collect 期间。
 */
public class JavaSourceIndex {

    private static final Logger log = LoggerFactory.getLogger(JavaSourceIndex.class);
    private static final Set<String> IGNORED = Set.of(
            ".git", ".idea", ".project-store", "node_modules", "target", "build", "dist", "out", ".svn");

    private final Map<String, Path> classNameToPath = new HashMap<>();

    public static JavaSourceIndex build(Path root) {
        JavaSourceIndex index = new JavaSourceIndex();
        if (root == null || !Files.exists(root)) {
            return index;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !shouldIgnore(p))
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        String simpleClass = fileName.substring(0, fileName.length() - ".java".length());
                        index.classNameToPath.putIfAbsent(simpleClass, p);
                    });
        } catch (IOException ex) {
            log.warn("[JavaSourceIndex] walk failed: {}", root, ex);
        }
        return index;
    }

    public Optional<Path> find(String simpleClassName) {
        if (simpleClassName == null || simpleClassName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(classNameToPath.get(simpleClassName.trim()));
    }

    public Set<String> knownClassNames() {
        return new LinkedHashSet<>(classNameToPath.keySet());
    }

    private static boolean shouldIgnore(Path path) {
        for (Path segment : path) {
            if (IGNORED.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
