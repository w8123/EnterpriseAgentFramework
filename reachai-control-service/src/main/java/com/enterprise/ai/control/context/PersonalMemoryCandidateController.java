package com.enterprise.ai.control.context;

import com.enterprise.ai.control.context.PersonalMemoryCandidateService.CandidatePage;
import com.enterprise.ai.control.context.PersonalMemoryCandidateService.CandidateView;
import com.enterprise.ai.control.context.PersonalMemoryCandidateService.ReviewCommand;
import com.enterprise.ai.control.context.PersonalMemoryCandidateService.ReviewResult;
import com.enterprise.ai.control.context.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PersonalMemoryCandidateController {

    private final PersonalMemoryIdentityResolver identityResolver;
    private final PersonalMemoryCandidateService candidateService;

    @GetMapping("/api/context/personal-memory-candidates")
    public ResponseEntity<CandidatePage> list(
            HttpServletRequest request,
            @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(defaultValue = "0") Integer offset) {
        return ResponseEntity.ok(candidateService.list(principal(request, tenantId), status, limit, offset));
    }

    @PostMapping("/api/context/personal-memory-candidates/{id}/approve")
    public ResponseEntity<ReviewResult> approve(
            HttpServletRequest request,
            @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
            @PathVariable Long id,
            @RequestBody(required = false) ReviewCommand command) {
        return ResponseEntity.ok(candidateService.approve(principal(request, tenantId), id, command));
    }

    @PostMapping("/api/context/personal-memory-candidates/{id}/reject")
    public ResponseEntity<CandidateView> reject(
            HttpServletRequest request,
            @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
            @PathVariable Long id,
            @RequestBody(required = false) ReviewCommand command) {
        return ResponseEntity.ok(candidateService.reject(principal(request, tenantId), id, command));
    }

    private PersonalMemoryPrincipal principal(HttpServletRequest request, String tenantId) {
        return identityResolver.resolve(request, tenantId);
    }
}
