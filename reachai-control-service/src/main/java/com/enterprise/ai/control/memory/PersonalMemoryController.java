package com.enterprise.ai.control.memory;

import com.enterprise.ai.control.memory.PersonalMemoryCandidateService.CandidatePage;
import com.enterprise.ai.control.memory.PersonalMemoryCandidateService.CandidateView;
import com.enterprise.ai.control.memory.PersonalMemoryCandidateService.ReviewCommand;
import com.enterprise.ai.control.memory.PersonalMemoryCandidateService.ReviewResult;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver.PersonalMemoryPrincipal;
import com.enterprise.ai.control.memory.PersonalMemoryService.AuditView;
import com.enterprise.ai.control.memory.PersonalMemoryService.EraseAllCommand;
import com.enterprise.ai.control.memory.PersonalMemoryService.EraseAllView;
import com.enterprise.ai.control.memory.PersonalMemoryService.ExportView;
import com.enterprise.ai.control.memory.PersonalMemoryService.ForgetView;
import com.enterprise.ai.control.memory.PersonalMemoryService.MemoryPage;
import com.enterprise.ai.control.memory.PersonalMemoryService.MemoryView;
import com.enterprise.ai.control.memory.PersonalMemoryService.RememberCommand;
import com.enterprise.ai.control.memory.PersonalMemoryService.RememberResult;
import com.enterprise.ai.control.memory.PersonalMemoryService.UpdateCommand;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** The signed-in platform user's own memories (个人中心 · 我的记忆). */
@RestController
@RequestMapping("/api/me/memories")
@RequiredArgsConstructor
public class PersonalMemoryController {

    private final PersonalMemoryIdentityResolver identityResolver;
    private final PersonalMemoryService memoryService;
    private final PersonalMemoryCandidateService candidateService;

    @GetMapping
    public MemoryPage list(HttpServletRequest request,
                           @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
                           @RequestParam(required = false) String type,
                           @RequestParam(required = false) String agentId,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(defaultValue = "50") Integer limit,
                           @RequestParam(defaultValue = "0") Integer offset) {
        return memoryService.list(principal(request, tenantId), type, agentId, keyword, limit, offset);
    }

    @PostMapping
    public ResponseEntity<RememberResult> remember(
            HttpServletRequest request,
            @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
            @RequestBody RememberCommand command) {
        RememberCommand manual = command == null ? null : new RememberCommand(command.type(), command.semanticKey(),
                command.title(), command.content(), command.tags(), command.expiresAt(), command.clientRequestId(),
                PersonalMemoryService.SOURCE_MANUAL, null, null, null);
        RememberResult result = memoryService.remember(principal(request, tenantId), manual);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @GetMapping("/{id}")
    public MemoryView get(HttpServletRequest request,
                          @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
                          @PathVariable Long id) {
        return memoryService.get(principal(request, tenantId), id);
    }

    @PutMapping("/{id}")
    public MemoryView update(HttpServletRequest request,
                             @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
                             @PathVariable Long id,
                             @RequestBody UpdateCommand command) {
        return memoryService.update(principal(request, tenantId), id, command);
    }

    @DeleteMapping("/{id}")
    public ForgetView forget(HttpServletRequest request,
                             @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
                             @PathVariable Long id) {
        return memoryService.forget(principal(request, tenantId), id);
    }

    @PostMapping("/erase-all")
    public EraseAllView eraseAll(HttpServletRequest request,
                                 @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
                                 @RequestBody EraseAllCommand command) {
        return memoryService.eraseAll(principal(request, tenantId), command);
    }

    @GetMapping("/audit")
    public List<AuditView> audit(HttpServletRequest request,
                                 @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
                                 @RequestParam(defaultValue = "100") Integer limit) {
        return memoryService.audit(principal(request, tenantId), limit);
    }

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExportView> export(
            HttpServletRequest request,
            @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId) {
        PersonalMemoryPrincipal principal = principal(request, tenantId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("reachai-personal-memory.json", StandardCharsets.UTF_8)
                .build());
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(memoryService.export(principal), headers, HttpStatus.OK);
    }

    @GetMapping("/candidates")
    public CandidatePage candidates(HttpServletRequest request,
                                    @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
                                    @RequestParam(defaultValue = "PENDING") String status,
                                    @RequestParam(defaultValue = "50") Integer limit,
                                    @RequestParam(defaultValue = "0") Integer offset) {
        return candidateService.list(principal(request, tenantId), status, limit, offset);
    }

    @PostMapping("/candidates/{id}/approve")
    public ReviewResult approve(HttpServletRequest request,
                                @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
                                @PathVariable Long id,
                                @RequestBody(required = false) ReviewCommand command) {
        return candidateService.approve(principal(request, tenantId), id, command);
    }

    @PostMapping("/candidates/{id}/reject")
    public CandidateView reject(HttpServletRequest request,
                                @RequestHeader(value = PersonalMemoryIdentityResolver.TENANT_HEADER, required = false) String tenantId,
                                @PathVariable Long id) {
        return candidateService.reject(principal(request, tenantId), id);
    }

    private PersonalMemoryPrincipal principal(HttpServletRequest request, String tenantId) {
        return identityResolver.resolve(request, tenantId);
    }
}
