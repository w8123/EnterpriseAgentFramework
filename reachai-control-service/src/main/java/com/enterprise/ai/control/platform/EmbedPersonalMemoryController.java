package com.enterprise.ai.control.platform;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.control.memory.PersonalMemoryCandidateService;
import com.enterprise.ai.control.memory.PersonalMemoryCandidateService.CandidatePage;
import com.enterprise.ai.control.memory.PersonalMemoryCandidateService.CandidateView;
import com.enterprise.ai.control.memory.PersonalMemoryCandidateService.ReviewResult;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService;
import com.enterprise.ai.control.memory.PersonalMemoryService.EraseAllCommand;
import com.enterprise.ai.control.memory.PersonalMemoryService.EraseAllView;
import com.enterprise.ai.control.memory.PersonalMemoryService.ForgetView;
import com.enterprise.ai.control.memory.PersonalMemoryService.MemoryPage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.function.Function;

/**
 * The embedded-chat end user's own memories (嵌入式对话 · 我的记忆).
 *
 * <p>The owner is the same one embedded chat recalls and writes for: the verified embed token's tenant and
 * external (or global) user. Nothing in the request can name another owner.
 */
@RestController
@RequestMapping("/api/embed/memories")
@RequiredArgsConstructor
public class EmbedPersonalMemoryController {

    private final PlatformEmbedTokenService tokenService;
    private final PersonalMemoryService memoryService;
    private final PersonalMemoryCandidateService candidateService;

    @GetMapping
    public ResponseEntity<ApiResult<MemoryPage>> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(defaultValue = "0") Integer offset) {
        return handle(authorization, owner -> memoryService.list(owner, type, agentId, keyword, limit, offset));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResult<ForgetView>> forget(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable Long id) {
        return handle(authorization, owner -> memoryService.forget(owner, id));
    }

    @PostMapping("/erase-all")
    public ResponseEntity<ApiResult<EraseAllView>> eraseAll(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody EraseAllCommand command) {
        return handle(authorization, owner -> memoryService.eraseAll(owner, command));
    }

    @GetMapping("/candidates")
    public ResponseEntity<ApiResult<CandidatePage>> candidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(defaultValue = "0") Integer offset) {
        return handle(authorization, owner -> candidateService.list(owner, "PENDING", limit, offset));
    }

    @PostMapping("/candidates/{id}/approve")
    public ResponseEntity<ApiResult<ReviewResult>> approve(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable Long id) {
        return handle(authorization, owner -> candidateService.approve(owner, id, null));
    }

    @PostMapping("/candidates/{id}/reject")
    public ResponseEntity<ApiResult<CandidateView>> reject(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable Long id) {
        return handle(authorization, owner -> candidateService.reject(owner, id));
    }

    private <T> ResponseEntity<ApiResult<T>> handle(String authorization, Function<PersonalMemoryPrincipal, T> action) {
        PersonalMemoryPrincipal owner;
        try {
            owner = owner(authorization);
        } catch (PlatformEmbedTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResult.fail(401, ex.getMessage()));
        }
        try {
            return ResponseEntity.ok(ApiResult.ok(action.apply(owner)));
        } catch (ResponseStatusException ex) {
            int status = ex.getStatusCode().value();
            return ResponseEntity.status(status).body(ApiResult.fail(status, ex.getReason()));
        }
    }

    PersonalMemoryPrincipal owner(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new PlatformEmbedTokenException("Authorization Bearer embed token is required");
        }
        PlatformEmbedTokenClaims claims = tokenService.verify(authorization.substring("Bearer ".length()).trim());
        String user = StringUtils.hasText(claims.getExternalUserId())
                ? claims.getExternalUserId().trim()
                : (StringUtils.hasText(claims.getGlobalUserId()) ? claims.getGlobalUserId().trim() : null);
        if (user == null) {
            throw new PlatformEmbedTokenException("embed token has no user");
        }
        // Matches how embedded chat derives the owner for recall and extraction.
        String tenant = StringUtils.hasText(claims.getTenantId())
                ? claims.getTenantId().trim().toLowerCase(Locale.ROOT)
                : "default";
        return new PersonalMemoryPrincipal(tenant, user, null, null, null);
    }
}
