package com.enterprise.ai.capability.aicoding;

import java.security.SecureRandom;
import java.util.HexFormat;

public final class AiCodingAccessKeys {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private AiCodingAccessKeys() {
    }

    public static String generate() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return "aic_" + HexFormat.of().formatHex(bytes);
    }
}
