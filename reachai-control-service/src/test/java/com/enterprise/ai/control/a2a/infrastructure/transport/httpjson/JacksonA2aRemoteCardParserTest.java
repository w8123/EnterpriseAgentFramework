package com.enterprise.ai.control.a2a.infrastructure.transport.httpjson;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonA2aRemoteCardParserTest {

    private final JacksonA2aRemoteCardParser parser = new JacksonA2aRemoteCardParser(
            new ObjectMapper(), new A2aOutboundTargetPolicy(new A2aHubProperties()));

    @Test
    void parsesA2a10HttpJsonIntoATypedCanonicalSnapshot() {
        var card = parser.parse(validCard().getBytes(StandardCharsets.UTF_8));

        assertEquals("Remote Reviewer", card.name());
        assertEquals("1.4.0", card.agentVersion());
        assertEquals(1, card.supportedInterfaces().size());
        assertTrue(card.supportedInterfaces().get(0).supportedByFirstRelease());
        assertEquals(64, card.agentCardSha256().length());
        assertEquals("NOT_PRESENT", card.signatureStatus());
        assertFalse(card.requiresAuthentication());
    }

    @Test
    void rejectsDuplicateJsonFieldsAndUnsupportedProtocolClaims() {
        String duplicate = validCard().replace("\"name\":\"Remote Reviewer\"",
                "\"name\":\"Remote Reviewer\",\"name\":\"Shadow\"");
        assertCode("A2A_REMOTE_CARD_INVALID", duplicate);

        String unsupported = validCard().replace("\"HTTP+JSON\"", "\"JSONRPC\"");
        assertCode("A2A_REMOTE_INTERFACE_NOT_SUPPORTED", unsupported);
    }

    @Test
    void rejectsDuplicateInterfacesSkillsAndUntypedSecurityDeclarations() {
        String duplicateInterface = validCard().replace(
                "{\"url\":\"https://8.8.8.8/a2a\",\"protocolBinding\":\"HTTP+JSON\",\"protocolVersion\":\"1.0\"}",
                "{\"url\":\"https://8.8.8.8/a2a\",\"protocolBinding\":\"HTTP+JSON\",\"protocolVersion\":\"1.0\"},"
                        + "{\"url\":\"https://8.8.8.8/a2a\",\"protocolBinding\":\"HTTP+JSON\",\"protocolVersion\":\"1.0\"}");
        assertCode("A2A_REMOTE_CARD_INVALID", duplicateInterface);

        String duplicateSkill = validCard().replace(
                "\"skills\":[{\"id\":\"review\",\"name\":\"Review\",\"description\":\"Review a request\",\"tags\":[\"review\"]}]",
                "\"skills\":[{\"id\":\"review\",\"name\":\"Review\",\"description\":\"Review a request\",\"tags\":[\"review\"]},"
                        + "{\"id\":\"review\",\"name\":\"Review 2\",\"description\":\"Duplicate\",\"tags\":[\"review\"]}]");
        assertCode("A2A_REMOTE_CARD_INVALID", duplicateSkill);

        String badSecurity = validCard().replace("\"skills\":[", "\"securitySchemes\":[],\"skills\":[");
        assertCode("A2A_REMOTE_CARD_INVALID", badSecurity);
    }

    private void assertCode(String expected, String json) {
        A2aDomainException failure = assertThrows(A2aDomainException.class,
                () -> parser.parse(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, failure.code());
    }

    private String validCard() {
        return """
                {
                  "name":"Remote Reviewer",
                  "description":"Reviews enterprise change requests",
                  "version":"1.4.0",
                  "supportedInterfaces":[
                    {"url":"https://8.8.8.8/a2a","protocolBinding":"HTTP+JSON","protocolVersion":"1.0"}
                  ],
                  "capabilities":{"streaming":false,"pushNotifications":false,"extendedAgentCard":false},
                  "defaultInputModes":["text/plain"],
                  "defaultOutputModes":["text/plain"],
                  "skills":[{"id":"review","name":"Review","description":"Review a request","tags":["review"]}]
                }
                """;
    }
}
