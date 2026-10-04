package com.enterprise.ai.runtime.workflow.bmapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Normal source inventory, public Control management, signed Runtime, real MVC upstream and H2 persistence. */
class HttpApiChangeImpactE2eIntegrationTest {
    @Test void dualSourceChangeRequiresExplicitNewWorkflowAndMcpReleaseAndNeverMigratesOldBindings() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Map<String, Object> evidence = new LinkedHashMap<>();
        try (var fixture = new BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture(json)) {
            fixture.start(); fixture.prepareChangeImpactSurface();
            assertEquals(200, fixture.changeScan("controller").statusCode());
            assertEquals(200, fixture.changeScan("openapi").statusCode());
            var first = fixture.multiSourceDetail(); assertEquals(2, first.summary().activeSourceCount());
            assertTrue(first.summary().sourceConfirmed()); assertEquals("DISCOVERED", first.summary().sourceStatus());
            accept(fixture); assertEquals(200, fixture.saveMultiSourceConnection().statusCode());
            fixture.changeSaveDraft(false); var oldVersion = fixture.changePublishVersion("v1.0.0");
            fixture.changePublishMcp(); evidence.put("initialCall", fixture.changeInitialPublished());
            evidence.put("initial", fixture.changeEvidence());
            var accepted = fixture.multiSourceDetail(); String oldHash = accepted.summary().acceptedContractHash();

            fixture.changeSources("display"); assertEquals(200, fixture.changeScan("openapi").statusCode());
            var display = fixture.multiSourceDetail(); assertEquals("ACCEPTED", display.summary().sourceStatus());
            assertEquals(oldHash, display.summary().candidateContractHash());
            assertNotEquals(accepted.summary().sourceSetRevision(), display.summary().sourceSetRevision());
            assertEquals("HTTP_API_PUBLISHED_PIN_STALE", fixture.changeRejectPublished());
            fixture.changeAssertOriginalUntouched(true); evidence.put("displayOnly", fixture.changeEvidence());

            fixture.changeSources("required"); assertEquals(200, fixture.changeScan("openapi").statusCode());
            assertEquals("CONFLICT", fixture.multiSourceDetail().summary().sourceStatus());
            assertNull(fixture.multiSourceDetail().summary().candidateContractHash()); fixture.changeRejectPublished();
            evidence.put("crossSourceConflict", fixture.changeEvidence());
            assertEquals(200, fixture.changeScan("controller").statusCode());
            var drift = fixture.multiSourceDetail(); assertEquals("CONTRACT_DRIFT", drift.summary().sourceStatus());
            assertEquals(oldHash, drift.summary().acceptedContractHash()); assertNotEquals(oldHash, drift.summary().candidateContractHash());
            fixture.changeRejectPublished(); evidence.put("contractDrift", fixture.changeEvidence());
            accept(fixture); fixture.changeAssertOriginalUntouched(true);
            assertEquals("HTTP_API_PUBLISHED_PIN_STALE", fixture.changeRejectPublished());
            evidence.put("acceptedOldStillRejected", fixture.changeEvidence());
            fixture.changeSaveDraft(true); var newVersion = fixture.changePublishVersion("v1.0.1");
            assertNotEquals(oldVersion.get("id"), newVersion.get("id")); fixture.changeAssertOriginalUntouched(true);
            fixture.changeRejectPublished(); evidence.put("newWorkflowOldMcp", fixture.changeEvidence());
            fixture.changePublishMcp(); var currentCall = fixture.changeCall();
            assertEquals(String.valueOf(newVersion.get("id")), String.valueOf(currentCall.get("workflowVersionId")));
            assertEquals("HTTP_API_PUBLISHED_PIN_STALE", fixture.changeRejectOriginalAfterUpdate());
            fixture.changeAssertOriginalUntouched(false); evidence.put("newCall", currentCall); evidence.put("republished", fixture.changeEvidence());

            fixture.changeReferenceUnavailable(true); assertEquals("UNKNOWN", fixture.changeReferences().get("runtimeEvidence"));
            evidence.put("referenceUnavailable", fixture.changeReferences()); fixture.changeReferenceUnavailable(false);
            assertEquals("COMPLETE", fixture.changeReferences().get("runtimeEvidence"));
            evidence.put("referenceRecovered", fixture.changeReferences());
            evidence.put("referenceIsolation", fixture.changeVerifyReferenceIsolation());
            verifySourceRemovalAndIdentity(fixture, evidence);
        }
        String directory = System.getProperty("bmapi.changeImpact.evidence");
        if (directory != null) {
            Path path = Path.of(directory).toAbsolutePath().resolve("http-persistence-evidence.json");
            Files.createDirectories(path.getParent()); assertFalse(Files.exists(path), "never replace earlier evidence");
            Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        }
    }

    static void verifySourceRemovalAndIdentity(BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture fixture,
                                               Map<String, Object> evidence) throws Exception {
        fixture.changeSources("partial"); assertEquals(200, fixture.changeScan("openapi").statusCode());
        var partial = fixture.multiSourceDetail(); assertEquals(2, partial.summary().activeSourceCount());
        assertFalse(partial.summary().sourceConfirmed()); assertEquals("SOURCE_UNCONFIRMED", partial.summary().sourceStatus());
        fixture.changeRejectPublished(); evidence.put("partialInventory", fixture.changeEvidence());
        fixture.changeSources("failed"); var failed = fixture.changeScan("openapi");
        assertTrue(failed.statusCode() >= 400); assertEquals(2, fixture.multiSourceDetail().summary().activeSourceCount());
        assertFalse(fixture.multiSourceDetail().summary().sourceConfirmed()); fixture.changeRejectPublished();
        evidence.put("failedScanStatus", failed.statusCode()); evidence.put("failedInventory", fixture.changeEvidence());
        fixture.changeSources("restore"); assertEquals(200, fixture.changeScan("openapi").statusCode());
        assertEquals(200, fixture.changeScan("controller").statusCode());
        assertEquals("ACCEPTED", fixture.multiSourceDetail().summary().sourceStatus());
        fixture.changeSources("one-removed"); assertEquals(200, fixture.changeScan("openapi").statusCode());
        assertEquals(1, fixture.multiSourceDetail().summary().activeSourceCount());
        assertTrue(fixture.multiSourceDetail().summary().sourceConfirmed());
        assertEquals("ACCEPTED", fixture.multiSourceDetail().summary().sourceStatus());
        fixture.changeRejectPublished(); evidence.put("oneRemoved", fixture.changeEvidence());
        fixture.changeSources("all-removed"); assertEquals(200, fixture.changeScan("controller").statusCode());
        assertEquals("SOURCE_MISSING", fixture.multiSourceDetail().summary().sourceStatus());
        evidence.put("allRemovedRejections", fixture.changeRejectConsoleAndDraft()); fixture.changeRejectPublished();
        evidence.put("allRemoved", fixture.changeEvidence());
        fixture.changeSources("restore"); assertEquals(200, fixture.changeScan("controller").statusCode());
        assertEquals(200, fixture.changeScan("openapi").statusCode());
        assertEquals("ACCEPTED", fixture.multiSourceDetail().summary().sourceStatus()); fixture.changeRejectPublished();
        evidence.put("restoredOldPinStillRejected", fixture.changeEvidence());
        fixture.changeSources("identity"); assertEquals(200, fixture.changeScan("openapi").statusCode());
        assertEquals(200, fixture.changeScan("controller").statusCode()); fixture.changeAssertIdentityNotMigrated();
        evidence.put("identityRejections", fixture.changeRejectConsoleAndDraft()); fixture.changeRejectPublished();
        evidence.put("newIdentityOldRefsPreserved", fixture.changeEvidence());
        assertEquals(2, evidenceCount(fixture.changeEvidence(), "upstreamRequests"), "only the two explicitly published MCP calls dispatch");
    }

    private static int evidenceCount(Map<String, Object> evidence, String key) { return ((Number)evidence.get(key)).intValue(); }
    static void accept(BusinessMethodWorkflowMcpE2eIntegrationTest.Fixture fixture) throws Exception {
        var response = fixture.multiSourcePublicRequest("POST", "/api/apis/1/accept",
                Map.of("expectedSourceSetRevision", fixture.multiSourceDetail().summary().sourceSetRevision()));
        assertEquals(200, response.statusCode(), "normal owner acceptance");
    }
}
