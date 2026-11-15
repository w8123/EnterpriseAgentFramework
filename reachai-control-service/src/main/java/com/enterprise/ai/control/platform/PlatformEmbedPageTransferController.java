package com.enterprise.ai.control.platform;

import com.enterprise.ai.common.dto.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/embed/chat/sessions/{sessionId}/page-transfers")
@RequiredArgsConstructor
public class PlatformEmbedPageTransferController {
    private final PlatformEmbedTokenService tokens;
    private final PlatformEmbedPageTransferService transfers;

    @PostMapping
    public ResponseEntity<ApiResult<PlatformEmbedPageTransferService.TransferTicket>> issue(
            @PathVariable String sessionId, @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.ok(transfers.issue(sessionId, verify(authorization))));
        } catch (PlatformEmbedTokenException denied) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException busy) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResult.fail(409, "page transfer is unavailable"));
        }
    }

    @PostMapping("/claim")
    public ResponseEntity<ApiResult<PlatformEmbedPageTransferService.TransferResult>> claim(
            @PathVariable String sessionId, @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody TransferClaim request) {
        try {
            if (request == null) throw new IllegalArgumentException("page transfer is required");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.ok(transfers.claim(sessionId,
                    verify(authorization), request.transferTicket(), request.bridgeActions(), request.sdkVersion())));
        } catch (PlatformEmbedTokenException denied) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException busy) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResult.fail(409, "page transfer is unavailable"));
        }
    }

    private PlatformEmbedTokenClaims verify(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            throw new PlatformEmbedTokenException("embed token required");
        }
        return tokens.verify(authorization.substring("Bearer ".length()).trim());
    }

    public record TransferClaim(String transferTicket, List<String> bridgeActions, String sdkVersion) { }
}
