package com.enterprise.ai.control.a2a.api.protocol;

import com.enterprise.ai.control.a2a.application.port.A2aPublicationRepository.PublishedCard;
import com.enterprise.ai.control.a2a.application.publication.A2aPublishedCardService;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reachai.a2a-hub", name = "enabled", havingValue = "true")
public class A2aAgentCardController {

    public static final String A2A_MEDIA_TYPE = "application/a2a+json";

    private final A2aPublishedCardService service;
    private final A2aHubProperties properties;

    @GetMapping(value = "/.well-known/agent-card.json",
            produces = {A2A_MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<String> card(HttpServletRequest request) {
        PublishedCard card = service.findByHost(request.getHeader(HttpHeaders.HOST)).orElse(null);
        if (card == null) {
            return ResponseEntity.notFound().build();
        }
        String etag = "\"" + card.agentCardSha256() + "\"";
        if (etag.equals(request.getHeader(HttpHeaders.IF_NONE_MATCH))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(card.agentCardSha256())
                    .build();
        }
        long maxAge = Math.max(0, properties.getAgentCardCacheMaxAge().toSeconds());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(A2A_MEDIA_TYPE))
                .cacheControl(CacheControl.maxAge(maxAge, TimeUnit.SECONDS).cachePublic())
                .eTag(card.agentCardSha256());
        if (card.publishedAt() != null) {
            response.lastModified(card.publishedAt().toInstant(ZoneOffset.UTC));
        }
        return response.body(card.agentCardJson());
    }
}
