package com.enterprise.ai.text.tooling.scanner.controller;

import com.enterprise.ai.text.tooling.scanner.ScanOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 增量：按 mtime 或（尝试）git 变更名过滤 .java 路径；git 不可用时回退 mtime 并打 warn。
 */
final class IncrementalFileFilter {

    private static final Logger log = LoggerFactory.getLogger(IncrementalFileFilter.class);

    private IncrementalFileFilter() {
    }

    static List<Path> apply(List<Path> javaFiles, Path sourceRoot, long sinceMs, ScanOptions options) {
        if (sinceMs <= 0) {
            return javaFiles;
        }
        if (options == null || options.getIncrementalMode() == null
                || ScanOptions.MODE_OFF.equalsIgnoreCase(options.getIncrementalMode())) {
            return javaFiles;
        }
        String mode = options.getIncrementalMode().trim().toUpperCase(Locale.ROOT);
        if (ScanOptions.MODE_MTIME.equals(mode) || "MTIME".equals(mode)) {
            return filterMtime(javaFiles, sinceMs);
        }
        if (ScanOptions.MODE_GIT.equals(mode) || "GIT_DIFF".equals(mode)) {
            Set<String> relFromGit = tryGitChangedRelativePaths(sourceRoot, sinceMs);
            if (relFromGit == null) {
                log.warn("git 未可用或非 git 根目录，GIT_DIFF 增量子模式按文件 mtime 过滤");
                return filterMtime(javaFiles, sinceMs);
            }
            return filterByRelPaths(sourceRoot, javaFiles, relFromGit);
        }
        return javaFiles;
    }

    private static List<Path> filterMtime(List<Path> javaFiles, long sinceMs) {
        List<Path> out = new ArrayList<>();
        for (Path p : javaFiles) {
            try {
                if (Files.getLastModifiedTime(p).toMillis() > sinceMs) {
                    out.add(p);
                }
            } catch (IOException e) {
                out.add(p);
            }
        }
        return out;
    }

    private static List<Path> filterByRelPaths(Path sourceRoot, List<Path> javaFiles, Set<String> rel) {
        if (rel.isEmpty()) {
            return new ArrayList<>();
        }
        Path base = findGitOrScanRoot(sourceRoot);
        List<Path> out = new ArrayList<>();
        for (Path p : javaFiles) {
            try {
                String relP = base.relativize(p.normalize()).toString().replace("\\", "/");
                for (String g : rel) {
                    if (g.equals(relP) || g.endsWith("/" + relP) || relP.endsWith(g)) {
                        out.add(p);
                        break;
                    }
                }
            } catch (Exception ignored) {
                // non-under-base path: skip
            }
        }
        return out;
    }

    private static Set<String> tryGitChangedRelativePaths(Path sourceRoot, long sinceMs) {
        Path root = findGitOrScanRoot(sourceRoot);
        try {
            String beforeIso = Instant.ofEpochMilli(sinceMs).toString();
            Process pr = new ProcessBuilder(
                    "git", "-C", root.toString(), "rev-list", "-1", "--before=" + beforeIso, "HEAD")
                    .redirectErrorStream(true)
                    .start();
            if (!pr.waitFor(8, TimeUnit.SECONDS) || pr.exitValue() != 0) {
                return null;
            }
            String oldRev = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (oldRev.isEmpty()) {
                return new HashSet<>();
            }
            Process d = new ProcessBuilder("git", "-C", root.toString(), "diff", "--name-only", oldRev, "HEAD", "--", ".")
                    .redirectErrorStream(true)
                    .start();
            if (!d.waitFor(3, TimeUnit.MINUTES) || d.exitValue() != 0) {
                return null;
            }
            String raw = new String(d.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Set<String> out = new HashSet<>();
            for (String line : raw.split("\r?\n")) {
                String t = line.trim().replace("\\", "/");
                if (t.isEmpty() || !t.endsWith(".java")) {
                    continue;
                }
                out.add(t);
            }
            return out;
        } catch (Exception ex) {
            log.debug("git incremental: {}", ex.toString());
            return null;
        }
    }

    private static Path findGitOrScanRoot(Path p) {
        if (p == null) {
            return Path.of(".");
        }
        Path c = Files.isDirectory(p) ? p : p.getParent();
        if (c == null) {
            return p;
        }
        Path cur = c;
        for (int i = 0; i < 32; i++) {
            if (Files.isDirectory(cur.resolve(".git"))) {
                return cur;
            }
            Path paren = cur.getParent();
            if (paren == null || paren.equals(cur)) {
                break;
            }
            cur = paren;
        }
        return c;
    }
}
