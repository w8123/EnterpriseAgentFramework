package com.enterprise.ai.reach.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Token broker for the browser SDK: exchanges the host's logged-in user for a
 * short-lived ReachAI Embed Token. The project secret never leaves the server.
 */
@RestController
@RequestMapping("/reachai/embed")
public class ReachAiEmbedTokenEndpoint {

    private static final Logger log = LoggerFactory.getLogger(ReachAiEmbedTokenEndpoint.class);

    private final ReachAiEmbedPrincipalResolver principalResolver;
    private final ReachAiEmbedTokenClient tokenClient;

    public ReachAiEmbedTokenEndpoint(ReachAiEmbedPrincipalResolver principalResolver,
                                     ReachAiEmbedTokenClient tokenClient) {
        this.principalResolver = principalResolver;
        this.tokenClient = tokenClient;
    }

    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> token(
            @RequestHeader(value = "Origin", required = false) String originHeader,
            @RequestBody(required = false) TokenRequest body) {
        TokenRequest request = body == null ? new TokenRequest() : body;
        if (!hasText(request.getAgentId()) || !hasText(request.getPageInstanceId())) {
            return error(HttpStatus.BAD_REQUEST, "agentId and pageInstanceId are required");
        }
        // Browsers always send Origin on POST; prefer it so a page cannot claim another origin.
        String origin = hasText(originHeader) ? originHeader.trim() : trimToNull(request.getOrigin());
        if (origin == null) {
            return error(HttpStatus.BAD_REQUEST, "origin is required");
        }
        if (hasText(originHeader) && hasText(request.getOrigin())
                && !originHeader.trim().equalsIgnoreCase(request.getOrigin().trim())) {
            return error(HttpStatus.BAD_REQUEST, "origin does not match the request Origin header");
        }
        ReachAiEmbedPrincipal principal = principalResolver.resolve(request.getAgentId().trim());
        if (principal == null || !hasText(principal.getExternalUserId())) {
            return error(HttpStatus.UNAUTHORIZED, "login required");
        }
        try {
            ReachAiEmbedTokenResult result = tokenClient.exchange(new ReachAiEmbedTokenRequest(
                    request.getAgentId().trim(),
                    request.getPageKey(),
                    request.getPageInstanceId().trim(),
                    request.getRoute(),
                    origin,
                    principal));
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("token", result.getToken());
            response.put("expiresIn", result.getExpiresIn());
            response.put("sessionHint", result.getSessionHint());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            log.warn("[ReachAI Embed] token exchange failed agentId={} origin={} error={}",
                    request.getAgentId(), origin, ex.getMessage());
            return error(HttpStatus.BAD_GATEWAY,
                    "ReachAI refused or failed to issue an Embed Token; check the Agent embed settings "
                            + "(allowed origins and agents) in ReachAI");
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(Collections.<String, Object>singletonMap("message", message));
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** Page context the browser SDK passes to its tokenProvider. */
    public static class TokenRequest {
        private String agentId;
        private String pageKey;
        private String pageInstanceId;
        private String route;
        private String origin;

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public String getPageKey() {
            return pageKey;
        }

        public void setPageKey(String pageKey) {
            this.pageKey = pageKey;
        }

        public String getPageInstanceId() {
            return pageInstanceId;
        }

        public void setPageInstanceId(String pageInstanceId) {
            this.pageInstanceId = pageInstanceId;
        }

        public String getRoute() {
            return route;
        }

        public void setRoute(String route) {
            this.route = route;
        }

        public String getOrigin() {
            return origin;
        }

        public void setOrigin(String origin) {
            this.origin = origin;
        }
    }
}
