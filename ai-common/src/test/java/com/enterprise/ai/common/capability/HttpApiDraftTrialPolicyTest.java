package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HttpApiDraftTrialPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void graphDigestBindsTheExactSavedBytes() {
        String graph = "{\"nodes\":[]}";
        assertEquals(64, HttpApiDraftTrialPolicy.graphSha256(graph).length());
        assertNotEquals(HttpApiDraftTrialPolicy.graphSha256(graph), HttpApiDraftTrialPolicy.graphSha256(graph + " "));
    }

    @Test void getAloneNeverProvesReadOnlyAndMethodMustMatchOwnerContract() throws Exception {
        for (String sideEffect : List.of("UNKNOWN", "WRITE", "READ", "")) {
            assertFalse(HttpApiDraftTrialPolicy.readOnlyOwner("GET", json.readTree(
                    "{\"identity\":{\"method\":\"GET\"},\"sideEffect\":\"" + sideEffect + "\"}")));
        }
        var readOnly = json.readTree("{\"identity\":{\"method\":\"GET\"},\"sideEffect\":\"READ_ONLY\"}");
        assertTrue(HttpApiDraftTrialPolicy.readOnlyOwner("GET", readOnly));
        assertFalse(HttpApiDraftTrialPolicy.readOnlyOwner("POST", readOnly));
        assertFalse(HttpApiDraftTrialPolicy.readOnlyOwner("HEAD", readOnly));
    }
}
