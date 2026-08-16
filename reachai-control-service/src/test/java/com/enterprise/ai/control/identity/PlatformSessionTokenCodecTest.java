package com.enterprise.ai.control.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformSessionTokenCodecTest {

    private final PlatformSessionTokenCodec codec = new PlatformSessionTokenCodec();

    @Test
    void issuesOpaqueTokensAndStableDigests() {
        String token = codec.issueRawToken();

        assertTrue(token.startsWith("pat_"));
        assertEquals(64, codec.digest(token).length());
        assertEquals(codec.digest(token), codec.digest(token));
        assertFalse(codec.digest(token).equals(codec.digest(token + "x")));
    }
}
