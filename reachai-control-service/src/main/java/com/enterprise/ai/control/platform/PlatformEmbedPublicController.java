package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/embed")
@RequiredArgsConstructor
public class PlatformEmbedPublicController {

    private final PlatformEmbedTokenService tokenService;
    private final PlatformEmbedSessionService sessionService;
    private final PlatformEmbedChatEventService chatEventService;
    private final CapabilityProxyClient capabilityProxyClient;
    private final RuntimeProxyClient runtimeProxyClient;
    private final PlatformPageActionEventMapper pageActionEventMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/token/exchange")
    public ResponseEntity<ApiResult<EmbedTokenExchangeResponse>> exchangeToken(
            @RequestHeader(value = "X-ReachAI-App-Key", required = false) String reachAiAppKey,
            @RequestHeader(value = "X-EAF-App-Key", required = false) String eafAppKey,
            @RequestHeader(value = "X-ReachAI-Timestamp", required = false) String reachAiTimestamp,
            @RequestHeader(value = "X-EAF-Timestamp", required = false) String eafTimestamp,
            @RequestHeader(value = "X-ReachAI-Nonce", required = false) String reachAiNonce,
            @RequestHeader(value = "X-EAF-Nonce", required = false) String eafNonce,
            @RequestHeader(value = "X-ReachAI-Signature", required = false) String reachAiSignature,
            @RequestHeader(value = "X-EAF-Signature", required = false) String eafSignature,
            @RequestBody EmbedTokenExchangeRequest request) {
        try {
            requireText(request == null ? null : request.projectCode(), "projectCode");
            requireText(request.agentId(), "agentId");
            requireText(request.pageInstanceId(), "pageInstanceId");
            requireText(request.origin(), "origin");
            BusinessPrincipal principal = request.principal();
            if (principal == null || !StringUtils.hasText(principal.externalUserId())) {
                throw new PlatformEmbedTokenException("principal.externalUserId is required");
            }
            Map<String, Object> verified = verifyWithCapability(
                    firstText(reachAiAppKey, eafAppKey),
                    firstText(reachAiTimestamp, eafTimestamp),
                    firstText(reachAiNonce, eafNonce),
                    firstText(reachAiSignature, eafSignature),
                    request);
            ensureRuntimeAgentAvailable(request.projectCode(), request.agentId());
            PlatformEmbedTokenIssueResult token = tokenService.issue(PlatformEmbedTokenIssueCommand.builder()
                    .tenantId("default")
                    .appId(request.projectCode())
                    .projectCode(request.projectCode())
                    .agentId(request.agentId())
                    .pageKey(request.pageKey())
                    .pageInstanceId(request.pageInstanceId())
                    .route(request.route())
                    .origin(request.origin())
                    .ttlSeconds(asInteger(verified.get("tokenTtlSeconds")))
                    .principal(new PlatformEmbedTokenIssueCommand.BusinessPrincipal(
                            principal.externalUserId(),
                            firstText(principal.globalUserId(), principal.externalUserId()),
                            principal.userName(),
                            principal.roles() == null ? List.of() : principal.roles(),
                            principal.attributes() == null ? Map.of() : principal.attributes()))
                    .build());
            Map<String, String> sessionHint = new LinkedHashMap<>();
            sessionHint.put("appId", request.projectCode());
            sessionHint.put("agentId", request.agentId());
            sessionHint.put("pageKey", StringUtils.hasText(request.pageKey()) ? request.pageKey() : "");
            sessionHint.put("pageInstanceId", request.pageInstanceId());
            return ResponseEntity.ok(ApiResult.ok(new EmbedTokenExchangeResponse(
                    token.token(),
                    token.expiresIn(),
                    sessionHint)));
        } catch (PlatformEmbedTokenException | IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResult.fail(403, ex.getMessage()));
        }
    }

    @PostMapping("/chat/sessions")
    public ResponseEntity<ApiResult<EmbedChatSessionResponse>> createSession(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody EmbedChatSessionCreateRequest request) {
        try {
            PlatformEmbedTokenClaims claims = verifyBearer(authorization);
            PlatformEmbedSessionEntity session = sessionService.create(
                    claims,
                    request == null ? null : request.pageKey(),
                    request == null ? null : request.pageInstanceId(),
                    request == null ? null : request.route(),
                    request == null ? List.of() : request.bridgeActions(),
                    request == null ? null : request.sdkVersion());
            Map<String, Object> principal = Map.of(
                    "tenantId", claims.getTenantId(),
                    "appId", claims.getAppId(),
                    "externalUserId", claims.getExternalUserId(),
                    "globalUserId", claims.getGlobalUserId());
            return ResponseEntity.ok(ApiResult.ok(new EmbedChatSessionResponse(
                    session.getSessionId(),
                    session.getAgentId(),
                    principal)));
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(401, ex.getMessage()));
        }
    }

    @PostMapping("/chat/sessions/{sessionId}/messages")
    public ResponseEntity<ApiResult<EmbedChatMessageResponse>> sendMessage(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody EmbedChatMessageRequest request) {
        try {
            return ResponseEntity.ok(ApiResult.ok(executeEmbedMessage(sessionId, authorization, request)));
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(401, ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResult.fail(400, ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResult.fail(403, ex.getMessage()));
        }
    }

    @PostMapping(value = "/chat/sessions/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamMessage(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody EmbedChatMessageRequest request) {
        try {
            EmbedChatMessageResponse response = executeEmbedMessage(sessionId, authorization, request);
            SseEmitter emitter = new SseEmitter();
            if (StringUtils.hasText(response.answer())) {
                emitter.send(SseEmitter.event()
                        .name("message.delta")
                        .data(Map.of("text", response.answer())));
            }
            if (response.uiRequest() != null) {
                emitter.send(SseEmitter.event()
                        .name("ui.requested")
                        .data(response.uiRequest()));
            }
            emitter.send(SseEmitter.event()
                    .name("message.completed")
                    .data(response));
            emitter.complete();
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(emitter);
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IOException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/chat/sessions/{sessionId}/page-actions/pending")
    public ResponseEntity<ApiResult<List<PageActionDispatchRequest>>> listPendingPageActions(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            PlatformEmbedTokenClaims claims = verifyBearer(authorization);
            PlatformEmbedSessionEntity session = sessionService.requireActiveSession(sessionId, claims);
            List<PageActionDispatchRequest> requests = pageActionEventMapper.selectList(
                            new LambdaQueryWrapper<PlatformPageActionEventEntity>()
                                    .eq(PlatformPageActionEventEntity::getSessionId, sessionId)
                                    .eq(PlatformPageActionEventEntity::getStatus, "REQUESTED")
                                    .orderByAsc(PlatformPageActionEventEntity::getId)
                                    .last("limit " + safeLimit(limit, 10)))
                    .stream()
                    .map(event -> toPageActionDispatch(session, event))
                    .toList();
            return ResponseEntity.ok(ApiResult.ok(requests));
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(401, ex.getMessage()));
        }
    }

    @PostMapping("/chat/sessions/{sessionId}/page-actions/{requestId}/result")
    public ResponseEntity<ApiResult<PageActionResultResponse>> submitPageActionResult(
            @PathVariable String sessionId,
            @PathVariable String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody PageActionResultRequest request) {
        try {
            PlatformEmbedTokenClaims claims = verifyBearer(authorization);
            sessionService.requireActiveSession(sessionId, claims);
            String normalizedRequestId = requiredRequestText(requestId, "requestId");
            if (request != null
                    && StringUtils.hasText(request.requestId())
                    && !normalizedRequestId.equals(request.requestId().trim())) {
                throw new IllegalArgumentException("page action result requestId does not match path");
            }
            PlatformPageActionEventEntity event = pageActionEventMapper.selectOne(
                    new LambdaQueryWrapper<PlatformPageActionEventEntity>()
                            .eq(PlatformPageActionEventEntity::getSessionId, sessionId)
                            .eq(PlatformPageActionEventEntity::getRequestId, normalizedRequestId)
                            .last("limit 1"));
            if (event == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResult.fail(404, "page action request not found: " + normalizedRequestId));
            }
            if (request != null
                    && StringUtils.hasText(request.actionKey())
                    && StringUtils.hasText(event.getActionKey())
                    && !event.getActionKey().equals(request.actionKey().trim())) {
                throw new IllegalArgumentException("page action result actionKey does not match request");
            }
            String status = normalizePageActionStatus(request == null ? null : request.status());
            event.setStatus(status);
            event.setResultJson(toJson(request == null ? Map.of("requestId", normalizedRequestId, "status", status) : request));
            event.setErrorMessage(text(request == null ? null : request.error()));
            event.setCompletedAt(LocalDateTime.now());
            pageActionEventMapper.updateById(event);
            return ResponseEntity.ok(ApiResult.ok(new PageActionResultResponse(
                    event.getRequestId(),
                    event.getActionKey(),
                    event.getStatus(),
                    event.getErrorMessage())));
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(401, ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResult.fail(400, ex.getMessage()));
        }
    }

    private EmbedChatMessageResponse executeEmbedMessage(String sessionId,
                                                         String authorization,
                                                         EmbedChatMessageRequest request) {
        PlatformEmbedTokenClaims claims = verifyBearer(authorization);
        PlatformEmbedSessionEntity session = sessionService.requireActiveSession(sessionId, claims);
        String message = request == null ? null : request.message();
        requireText(message, "message");
        chatEventService.recordUserMessage(session, message);
        Map<String, Object> runtimeBody = new LinkedHashMap<>();
        runtimeBody.put("agentDefinitionId", session.getAgentId());
        runtimeBody.put("sessionId", sessionId);
        runtimeBody.put("userId", claims.getExternalUserId());
        runtimeBody.put("message", message);
        runtimeBody.put("projectCode", session.getProjectCode());
        runtimeBody.put("pageKey", session.getPageKey());
        runtimeBody.put("route", session.getRoute());
        runtimeBody.put("intentHint", "EMBED_CHAT");
        runtimeBody.put("roles", claims.getRoles() == null ? List.of() : claims.getRoles());
        runtimeBody.put("metadata", Map.of(
                "tenantId", session.getTenantId(),
                "appId", session.getAppId(),
                "projectCode", session.getProjectCode(),
                "externalUserId", session.getExternalUserId(),
                "globalUserId", session.getGlobalUserId(),
                "pageInstanceId", session.getPageInstanceId(),
                "origin", session.getOrigin(),
                "route", session.getRoute()));
        ResponseEntity<Map<String, Object>> runtimeResponse = runtimeProxyClient.executeAgent(runtimeBody);
        if (!runtimeResponse.getStatusCode().is2xxSuccessful() || runtimeResponse.getBody() == null) {
            throw new IllegalStateException("runtime agent execution failed");
        }
        Map<String, Object> body = runtimeResponse.getBody();
        Map<String, Object> metadata = mapValue(body.get("metadata"));
        String answer = text(body.get("answer"));
        chatEventService.recordAssistantMessage(session, answer, body, text(metadata.get("traceId")));
        return new EmbedChatMessageResponse(
                firstText(text(body.get("sessionId")), sessionId),
                answer,
                text(body.get("intentType")),
                stringList(body.get("toolCalls")),
                metadata,
                body.get("uiRequest"));
    }

    private PageActionDispatchRequest toPageActionDispatch(PlatformEmbedSessionEntity session,
                                                           PlatformPageActionEventEntity event) {
        Map<String, Object> target = new LinkedHashMap<>();
        putIfText(target, "pageInstanceId", firstText(event.getTargetPageInstanceId(), session.getPageInstanceId()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfText(metadata, "tenantId", session.getTenantId());
        putIfText(metadata, "appId", session.getAppId());
        putIfText(metadata, "projectCode", session.getProjectCode());
        putIfText(metadata, "agentId", session.getAgentId());
        putIfText(metadata, "pageKey", session.getPageKey());
        putIfText(metadata, "route", session.getRoute());
        return new PageActionDispatchRequest(
                "page.action.requested",
                "1.0",
                event.getRequestId(),
                event.getActionKey(),
                event.getTitle(),
                event.getNodeId(),
                target,
                Boolean.TRUE.equals(event.getConfirmRequired()),
                parseJsonObject(event.getArgsJson()),
                metadata);
    }

    private PlatformEmbedTokenClaims verifyBearer(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new PlatformEmbedTokenException("Authorization Bearer embed token is required");
        }
        return tokenService.verify(authorization.substring("Bearer ".length()).trim());
    }

    private Map<String, Object> verifyWithCapability(String appKey,
                                                     String timestamp,
                                                     String nonce,
                                                     String signature,
                                                     EmbedTokenExchangeRequest request) {
        ResponseEntity<Map<String, Object>> response;
        try {
            response = capabilityProxyClient.verifyEmbedTokenExchange(
                    appKey,
                    timestamp,
                    nonce,
                    signature,
                    Map.of(
                            "projectCode", request.projectCode(),
                            "agentId", request.agentId(),
                            "origin", request.origin()));
        } catch (RuntimeException ex) {
            throw new PlatformEmbedTokenException("embed token exchange is not allowed", ex);
        }
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new PlatformEmbedTokenException("embed token exchange is not allowed");
        }
        return response.getBody();
    }

    private void ensureRuntimeAgentAvailable(String projectCode, String agentId) {
        ResponseEntity<Object> response = runtimeProxyClient.listAgents(null, projectCode, null);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new PlatformEmbedTokenException("agent not found: " + agentId);
        }
        for (Object item : extractItems(response.getBody())) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object id = firstValue(map, "id", "keySlug");
            Object keySlug = map.get("keySlug");
            if ((Objects.equals(String.valueOf(id), agentId) || Objects.equals(String.valueOf(keySlug), agentId))
                    && !Boolean.FALSE.equals(map.get("enabled"))) {
                Object agentProjectCode = map.get("projectCode");
                if (agentProjectCode == null || projectCode.equals(String.valueOf(agentProjectCode))) {
                    return;
                }
            }
        }
        throw new PlatformEmbedTokenException("agent not found: " + agentId);
    }

    private Collection<?> extractItems(Object body) {
        if (body instanceof Collection<?> collection) {
            return collection;
        }
        if (body instanceof Map<?, ?> map) {
            for (String key : List.of("data", "records", "items", "agents")) {
                Object value = map.get(key);
                if (value instanceof Collection<?> collection) {
                    return collection;
                }
            }
        }
        return List.of();
    }

    private int safeLimit(int requested, int fallback) {
        int value = requested <= 0 ? fallback : requested;
        return Math.min(Math.max(value, 1), 1000);
    }

    private Object firstValue(Map<?, ?> map, String first, String second) {
        Object value = map.get(first);
        return value == null ? map.get(second) : value;
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, val) -> result.put(String.valueOf(key), val));
        return result;
    }

    private Map<String, Object> parseJsonObject(String value) {
        if (!StringUtils.hasText(value)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(value, new TypeReference<>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("page action result json is invalid", ex);
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream()
                .map(String::valueOf)
                .filter(StringUtils::hasText)
                .toList();
    }

    private void requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new PlatformEmbedTokenException(name + " is required");
        }
    }

    private String requiredRequestText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private String normalizePageActionStatus(String status) {
        String normalized = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "SUCCESS";
        return switch (normalized) {
            case "SUCCESS", "FAILED", "CANCELLED", "ACTION_NOT_FOUND", "FORBIDDEN", "TIMEOUT" -> normalized;
            default -> throw new IllegalArgumentException("unsupported page action status: " + status);
        };
    }

    private void putIfText(Map<String, Object> map, String key, String value) {
        if (StringUtils.hasText(value)) {
            map.put(key, value);
        }
    }

    private String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    public record EmbedTokenExchangeRequest(
            String projectCode,
            String agentId,
            String pageKey,
            String pageInstanceId,
            String route,
            String origin,
            BusinessPrincipal principal) {
    }

    public record EmbedTokenExchangeResponse(
            String token,
            long expiresIn,
            Map<String, String> sessionHint) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BusinessPrincipal(
            String externalUserId,
            String globalUserId,
            @JsonAlias("displayName") String userName,
            List<String> roles,
            Map<String, Object> attributes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedChatMessageRequest(@JsonAlias({"content", "text"}) String message) {
    }

    public record EmbedChatMessageResponse(
            String sessionId,
            String answer,
            String intentType,
            List<String> toolCalls,
            Map<String, Object> metadata,
            Object uiRequest) {
    }

    public record EmbedChatSessionCreateRequest(
            String pageKey,
            String pageInstanceId,
            String route,
            List<String> bridgeActions,
            String sdkVersion) {
    }

    public record EmbedChatSessionResponse(
            String sessionId,
            String agentId,
            Map<String, Object> principal) {
    }

    public record PageActionDispatchRequest(
            String type,
            String protocolVersion,
            String requestId,
            String actionKey,
            String title,
            String nodeId,
            Map<String, Object> target,
            boolean confirm,
            Map<String, Object> args,
            Map<String, Object> metadata) {
    }

    public record PageActionResultRequest(
            String protocolVersion,
            String type,
            String requestId,
            String actionKey,
            String status,
            Object data,
            String error) {
    }

    public record PageActionResultResponse(
            String requestId,
            String actionKey,
            String status,
            String error) {
    }
}
