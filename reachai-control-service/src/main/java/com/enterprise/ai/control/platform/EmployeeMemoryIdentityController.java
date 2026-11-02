package com.enterprise.ai.control.platform;

import com.enterprise.ai.common.dto.ApiResult;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityChallengeService;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Both sides of a self-service link must use independently authenticated employee sessions. */
@RestController
@RequestMapping("/api/employee-portal/memory-identities")
@RequiredArgsConstructor
public class EmployeeMemoryIdentityController {
    private final EmployeePortalSessionService sessions;
    private final PersonalMemoryIdentityChallengeService challenges;
    private final PersonalMemoryIdentityLinkService links;

    @PostMapping("/challenges")
    public ResponseEntity<ApiResult<PersonalMemoryIdentityChallengeService.Challenge>> issue(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.ok(challenges.issue(bearer(authorization))));
    }

    @PostMapping("/confirm")
    public ApiResult<Long> confirm(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                   @RequestBody ConfirmCommand command) {
        return ApiResult.ok(challenges.confirm(bearer(authorization), command == null ? null : command.code()));
    }

    @DeleteMapping("/{linkId}")
    public ApiResult<Boolean> revoke(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                     @PathVariable long linkId) {
        var employee = sessions.resolve(bearer(authorization)).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "employee session expired"));
        links.revokeByEmployee(employee, linkId);
        return ApiResult.ok(Boolean.TRUE);
    }

    private static String bearer(String value) {
        if (!StringUtils.hasText(value) || !value.startsWith("Bearer ")) return null;
        return value.substring(7).trim();
    }

    public record ConfirmCommand(String code) { }
}
