package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** HTTP API read-only declaration and saved-graph digest shared by the Studio whole-draft read-only trial. */
public final class HttpApiDraftTrialPolicy {
    private HttpApiDraftTrialPolicy() { }

    public static boolean readOnlyOwner(String method, JsonNode acceptedContract) {
        if (acceptedContract == null || !acceptedContract.isObject()) return false;
        String contractMethod = acceptedContract.path("identity").path("method").asText();
        String sideEffect = acceptedContract.path("sideEffect").asText();
        return Set.of("GET", "HEAD").contains(method)
                && Objects.equals(method, contractMethod)
                && Set.of("READ_ONLY", "NONE").contains(sideEffect);
    }

    public static String graphSha256(String graphJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(graphJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception invalid) {
            throw new IllegalStateException("SHA-256 unavailable", invalid);
        }
    }
}
