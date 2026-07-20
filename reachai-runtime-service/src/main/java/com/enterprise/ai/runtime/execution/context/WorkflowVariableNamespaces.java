package com.enterprise.ai.runtime.execution.context;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Unified Workflow variable namespaces for Runtime, release validation and Studio selectors.
 *
 * <ul>
 *   <li>{@code input}/{@code message} — entry input (read-only)</li>
 *   <li>{@code params} — USER_INPUT / parameter bag (write only by designated nodes)</li>
 *   <li>{@code sys} — runtime system variables (read-only)</li>
 *   <li>{@code nodeOutput.&lt;nodeId&gt;} — raw per-node output</li>
 *   <li>{@code var.&lt;alias&gt;} — business namespace for {@code outputAlias} and VARIABLE_ASSIGN</li>
 *   <li>{@code lastOutput}/{@code previousOutput} — Runtime convenience only</li>
 * </ul>
 */
public final class WorkflowVariableNamespaces {

    public static final String VAR_ROOT = "var";
    public static final String NODE_OUTPUT_ROOT = "nodeOutput";
    public static final String PARAMS_ROOT = "params";
    public static final String SYS_ROOT = "sys";

    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Set<String> RESERVED_ROOTS = Set.of(
            "input",
            "message",
            "params",
            "sys",
            "nodeOutput",
            "lastOutput",
            "previousOutput",
            "route",
            "lastRoute",
            "var");

    private WorkflowVariableNamespaces() {
    }

    public static boolean isValidAlias(String alias) {
        return StringUtils.hasText(alias) && ALIAS_PATTERN.matcher(alias.trim()).matches();
    }

    public static boolean isReservedRoot(String root) {
        if (!StringUtils.hasText(root)) {
            return true;
        }
        String normalized = root.trim();
        if (normalized.startsWith("__")) {
            return true;
        }
        return RESERVED_ROOTS.contains(normalized);
    }

    public static boolean isReservedAlias(String alias) {
        if (!StringUtils.hasText(alias)) {
            return true;
        }
        String normalized = alias.trim();
        if (normalized.startsWith("__")) {
            return true;
        }
        return RESERVED_ROOTS.contains(normalized);
    }

    /**
     * Normalize a business write target into {@code var.&lt;alias&gt;} form.
     * Accepts {@code foo}, {@code var.foo}, rejects reserved roots and nested reserved paths.
     */
    public static String normalizeBusinessWriteTarget(String rawTarget) {
        String target = rawTarget == null ? "" : rawTarget.trim();
        if (target.startsWith("$.")) {
            target = target.substring(2);
        }
        if (!StringUtils.hasText(target)) {
            throw new IllegalArgumentException("Assignment target is required");
        }
        if (target.startsWith("__")) {
            throw new IllegalArgumentException("Assignment target must not use internal reserved prefix __*");
        }
        String path = target;
        if (path.startsWith(VAR_ROOT + ".")) {
            path = path.substring(VAR_ROOT.length() + 1);
        } else if (path.contains(".")) {
            String root = path.substring(0, path.indexOf('.'));
            if (isReservedRoot(root)) {
                throw new IllegalArgumentException("Assignment target must not write reserved namespace: " + root);
            }
            // Bare multi-segment business path -> var.<path>
        } else if (isReservedRoot(path)) {
            throw new IllegalArgumentException("Assignment target must not overwrite reserved name: " + path);
        }
        if (!StringUtils.hasText(path) || path.startsWith(".") || path.endsWith(".") || path.contains("..")) {
            throw new IllegalArgumentException("Assignment target path is invalid");
        }
        String[] segments = path.split("\\.", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (!StringUtils.hasText(segment)) {
                throw new IllegalArgumentException("Assignment target path contains empty segment");
            }
            if (!isValidAlias(segment)) {
                throw new IllegalArgumentException("Assignment target segment is invalid: " + segment);
            }
            if (i == 0 && isReservedAlias(segment)) {
                throw new IllegalArgumentException("Assignment target must not overwrite reserved name: " + segment);
            }
            if (i > 0 && isReservedRoot(segment)) {
                throw new IllegalArgumentException("Assignment target must not use reserved namespace segment: " + segment);
            }
        }
        return VAR_ROOT + "." + path;
    }

    public static String varPath(String alias) {
        if (!isValidAlias(alias)) {
            throw new IllegalArgumentException("outputAlias must be a simple identifier");
        }
        String normalized = alias.trim();
        if (isReservedAlias(normalized)) {
            throw new IllegalArgumentException("outputAlias must not use reserved name: " + normalized);
        }
        return VAR_ROOT + "." + normalized;
    }

    public static String lookupKey(String raw) {
        return raw == null ? "" : raw.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
