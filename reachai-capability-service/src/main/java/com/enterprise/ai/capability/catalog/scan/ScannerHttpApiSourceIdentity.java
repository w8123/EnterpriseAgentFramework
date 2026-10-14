package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceKind;

import java.util.Locale;
import java.util.regex.Pattern;

/** Matches the scanner's display location to its richer HTTP evidence; callers require a unique candidate. */
public final class ScannerHttpApiSourceIdentity {
    private static final Pattern JAVA_NAME = Pattern.compile("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*");

    private ScannerHttpApiSourceIdentity() { }

    public static boolean matches(HttpApiSourceKind kind, String observationLocation, String sourceLocation) {
        if (observationLocation == null || sourceLocation == null) return false;
        String[] observation = observationLocation.trim().split("#", -1);
        String[] source = sourceLocation.trim().split("#", -1);
        if (kind == HttpApiSourceKind.CONTROLLER_SCAN) {
            if (observation.length != 3 || source.length != 3
                    || !fileMatches(observation[0], source[0], ".java")) return false;
            String simpleClass = simpleName(source[1]);
            if (!JAVA_NAME.matcher(simpleClass).matches()
                    || !(observation[1].contains(".") ? observation[1].equals(source[1])
                    : observation[1].equals(simpleClass))) return false;
            String sourceMethod = methodName(source[2]);
            if (!JAVA_NAME.matcher(sourceMethod).matches()) return false;
            return observation[2].contains("(") ? observation[2].equals(source[2])
                    : observation[2].equals(sourceMethod);
        }
        if (kind == HttpApiSourceKind.OPENAPI_SCAN) {
            return observation.length == 2 && source.length == 2
                    && fileMatches(observation[0], source[0], null)
                    && observation[1].startsWith("/paths/")
                    && observation[1].equals(source[1]);
        }
        return false;
    }

    public static String method(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    public static String route(String contextPath, String endpointPath) {
        String value = ((contextPath == null ? "" : contextPath.trim()) + "/"
                + (endpointPath == null ? "" : endpointPath.trim())).replaceAll("/+", "/");
        if (!value.startsWith("/")) value = "/" + value;
        return value.length() > 1 && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean fileMatches(String observation, String source, String suffix) {
        String left = observation.replace('\\', '/');
        String right = source.replace('\\', '/');
        if (left.isBlank() || right.isBlank() || left.endsWith("/") || right.endsWith("/")) return false;
        if (suffix != null && (!left.endsWith(suffix) || !right.endsWith(suffix))) return false;
        return left.contains("/") ? left.equals(right) : left.equals(right.substring(right.lastIndexOf('/') + 1));
    }

    private static String simpleName(String value) { return value.substring(value.lastIndexOf('.') + 1); }
    private static String methodName(String value) {
        int arguments = value.indexOf('(');
        if (arguments < 0) return value;
        return value.endsWith(")") ? value.substring(0, arguments) : "";
    }
}
