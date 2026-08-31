package com.enterprise.ai.reach.sdk.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachAiClientConfigTest {

    /** Null, empty and whitespace-only values that every required field must reject. */
    private static final String[] INVALID_REQUIRED_VALUES = {null, "", "   "};

    private static ReachAiClientConfig build(String endpoint, String projectCode,
                                             String appKey, String appSecret) {
        return ReachAiClientConfig.builder()
                .endpoint(endpoint)
                .projectCode(projectCode)
                .appKey(appKey)
                .appSecret(appSecret)
                .build();
    }

    @Test
    void trimsAllFieldsAndRemovesAllTrailingEndpointSlashes() {
        ReachAiClientConfig config = ReachAiClientConfig.builder()
                .endpoint("  https://reachai.example.com/api/v1///  ")
                .projectCode("  demo  ")
                .projectName("  Demo Project  ")
                .appKey("  rak_demo  ")
                .appSecret("  ras_demo_secret  ")
                .build();

        assertEquals("https://reachai.example.com/api/v1", config.getEndpoint());
        assertEquals("demo", config.getProjectCode());
        assertEquals("Demo Project", config.getProjectName());
        assertEquals("rak_demo", config.getAppKey());
        assertEquals("ras_demo_secret", config.getAppSecret());
    }

    @Test
    void normalizesNullBlankAndWhitespaceProjectNameToNull() {
        assertNull(build("https://reachai.example.com/api/v1", "demo",
                "rak_demo", "ras_demo_secret").getProjectName());
        assertNull(ReachAiClientConfig.builder()
                .endpoint("https://reachai.example.com/api/v1")
                .projectCode("demo")
                .appKey("rak_demo")
                .appSecret("ras_demo_secret")
                .projectName(null)
                .build().getProjectName());
        assertNull(ReachAiClientConfig.builder()
                .endpoint("https://reachai.example.com/api/v1")
                .projectCode("demo")
                .appKey("rak_demo")
                .appSecret("ras_demo_secret")
                .projectName("")
                .build().getProjectName());
        assertNull(ReachAiClientConfig.builder()
                .endpoint("https://reachai.example.com/api/v1")
                .projectCode("demo")
                .appKey("rak_demo")
                .appSecret("ras_demo_secret")
                .projectName("   ")
                .build().getProjectName());

        assertEquals("Demo", ReachAiClientConfig.builder()
                .endpoint("https://reachai.example.com/api/v1")
                .projectCode("demo")
                .appKey("rak_demo")
                .appSecret("ras_demo_secret")
                .projectName("  Demo  ")
                .build().getProjectName());
    }

    @Test
    void rejectsNullAndBlankEndpoint() {
        for (String invalid : INVALID_REQUIRED_VALUES) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> build(invalid, "demo", "rak_demo", "ras_demo_secret"));
            assertTrue(ex.getMessage().contains("endpoint"),
                    "message should name endpoint: " + ex.getMessage());
        }
    }

    @Test
    void rejectsNullAndBlankProjectCode() {
        for (String invalid : INVALID_REQUIRED_VALUES) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> build("https://reachai.example.com/api/v1", invalid,
                            "rak_demo", "ras_demo_secret"));
            assertTrue(ex.getMessage().contains("projectCode"),
                    "message should name projectCode: " + ex.getMessage());
        }
    }

    @Test
    void rejectsNullAndBlankAppKey() {
        for (String invalid : INVALID_REQUIRED_VALUES) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> build("https://reachai.example.com/api/v1", "demo",
                            invalid, "ras_demo_secret"));
            assertTrue(ex.getMessage().contains("appKey"),
                    "message should name appKey: " + ex.getMessage());
        }
    }

    @Test
    void rejectsNullAndBlankAppSecret() {
        for (String invalid : INVALID_REQUIRED_VALUES) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> build("https://reachai.example.com/api/v1", "demo",
                            "rak_demo", invalid));
            assertTrue(ex.getMessage().contains("appSecret"),
                    "message should name appSecret: " + ex.getMessage());
        }
    }
}
